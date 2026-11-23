// Test di integrazione del caricamento di foto, con chiamate HTTP vere, PostGIS e Redis in Testcontainers e
// in più un MinIO vero avviato da questa classe. Riproduce il contratto usato dall'app: il backend rilascia
// una policy di presigned POST, il telefono carica il file direttamente su MinIO, poi il backend conferma il
// caricamento, ne verifica il contenuto e fornisce un URL firmato per scaricarlo. Il corpo multipart è
// costruito con MultipartBodyBuilder nello stesso ordine usato dal client Ktor dell'app
// (submitFormWithBinaryData): prima i campi della policy, poi la parte "file".
package it.unical.geosentinel.media;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica caricamento, conferma, verifica del contenuto, permessi e download delle foto allegate a
 * segnalazioni, sentieri, rifugi e profili utente.
 */
class MediaUploadIT extends AbstractIntegrationTest {

    /** Utente amministratore di MinIO, uguale alla chiave di accesso del profilo di test. */
    private static final String ACCESS_KEY = "test-access-key";

    /** Password dell'amministratore di MinIO, uguale alla chiave segreta del profilo di test. */
    private static final String SECRET_KEY = "test-secret-key";

    /** Bucket in cui l'applicazione salva i file. */
    private static final String BUCKET = "geosentinel-media";

    /** Finto JPEG di 2 KB: firma corretta seguita da byte di riempimento. */
    private static final byte[] JPEG = jpeg(2048);

    /**
     * Prefisso delle chiavi di staging. Il telefono carica sotto questo prefisso; dopo la verifica il file viene
     * copiato alla chiave finale, che è la stessa senza prefisso.
     */
    private static final String STAGING = "staging/";

    /**
     * Container MinIO, con la stessa immagine del docker compose di sviluppo (le immagini ufficiali
     * minio/minio non sono più scaricabili). Parte quando MinIO risponde al controllo di salute.
     */
    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z"))
                    .withCommand("server", "/data")
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    // Avvio del container una sola volta per JVM, come per PostGIS e Redis nella classe base.
    static {
        MINIO.start();
    }

    /**
     * Collega l'applicazione al MinIO del test. L'endpoint interno e quello pubblico coincidono, perché qui il
     * test fa sia la parte del backend sia quella del telefono.
     *
     * @param registry il registro delle proprietà fornito da Spring
     */
    @DynamicPropertySource
    static void minioProperties(DynamicPropertyRegistry registry) {
        registry.add("geosentinel.storage.minio.endpoint", MediaUploadIT::minioUrl);
        registry.add("geosentinel.storage.minio.public-endpoint", MediaUploadIT::minioUrl);
        registry.add("geosentinel.storage.minio.bucket", () -> BUCKET);
    }

    /**
     * Restituisce l'indirizzo di MinIO raggiungibile dall'host.
     *
     * @return l'URL con host e porta mappata del container
     */
    private static String minioUrl() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    /** Client MinIO con credenziali di amministratore, usato per preparare il bucket e controllare gli oggetti. */
    private final MinioClient admin = MinioClient.builder()
            .endpoint(minioUrl()).region("us-east-1").credentials(ACCESS_KEY, SECRET_KEY).build();

    /**
     * Crea il bucket se non esiste. Nell'ambiente di sviluppo lo crea il servizio minio-init del docker
     * compose; qui se ne occupa il test.
     */
    @BeforeEach
    void createBucket() throws Exception {
        if (!admin.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    /**
     * Un escursionista carica una foto sulla propria segnalazione e la conferma: la foto risulta {@code READY}
     * con dimensione e checksum. Un altro escursionista la trova tra gli allegati della segnalazione, ottiene un
     * URL firmato e scarica esattamente gli stessi byte, come JPEG e con disposizione "attachment".
     */
    @Test
    void hikerUploadsPhotoOnOwnHazardAndAnotherHikerDownloadsIt() {
        String hiker = obtainToken(Role.HIKER);
        UUID hazardId = createHazard(hiker);

        JsonNode session = requestUpload(hiker, JPEG.length, "HAZARD_REPORT", hazardId);
        assertThat(postToStorage(session, Map.of(), JPEG).status()).isEqualTo(204);

        HttpResult confirmed = confirm(hiker, session);
        assertThat(confirmed.status()).isEqualTo(200);
        JsonNode media = json(confirmed.body());
        assertThat(media.get("status").asString()).isEqualTo("READY");
        assertThat(media.get("sizeBytes").asLong()).isEqualTo(JPEG.length);
        assertThat(media.get("checksum").asString()).isNotBlank();

        String other = obtainToken(Role.HIKER);
        HttpResult attached = call(HttpMethod.GET,
                "/api/v1/media/attached?entityType=HAZARD_REPORT&entityId=" + hazardId, other, null);
        assertThat(attached.status()).isEqualTo(200);
        assertThat(json(attached.body()).get(0).get("id").asString()).isEqualTo(mediaId(session));

        HttpResult urlResult = call(HttpMethod.GET, "/api/v1/media/" + mediaId(session) + "/download-url", other, null);
        assertThat(urlResult.status()).isEqualTo(200);
        URI downloadUrl = URI.create(json(urlResult.body()).get("downloadUrl").asString());

        Map.Entry<HttpHeaders, byte[]> download = storageClient().get().uri(downloadUrl)
                .exchange((req, res) -> Map.entry(res.getHeaders(), res.getBody().readAllBytes()));
        assertThat(download.getValue()).isEqualTo(JPEG);
        assertThat(download.getKey().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(download.getKey().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");
    }

    /** Un gestore del parco carica una foto su un sentiero, e un escursionista la trova tra gli allegati. */
    @Test
    void parkManagerUploadsPhotoOnTrail() {
        String manager = obtainToken(Role.PARK_MANAGER);
        UUID trailId = insertTrail("Sentiero foto", "LINESTRING(16.40 39.30, 16.41 39.31)");

        JsonNode session = requestUpload(manager, JPEG.length, "TRAIL", trailId);
        assertThat(postToStorage(session, Map.of(), JPEG).status()).isEqualTo(204);
        assertThat(json(confirm(manager, session).body()).get("status").asString()).isEqualTo("READY");

        HttpResult attached = call(HttpMethod.GET,
                "/api/v1/media/attached?entityType=TRAIL&entityId=" + trailId, obtainToken(Role.HIKER), null);
        assertThat(json(attached.body())).hasSize(1);
    }

    /** Il gestore di un rifugio carica una foto sul proprio rifugio, e un escursionista la trova tra gli allegati. */
    @Test
    void refugeManagerUploadsPhotoOnOwnRefugeAndAHikerSeesIt() {
        var manager = createUser(Role.PARK_MANAGER);
        String token = tokenFor(manager);
        UUID refugeId = insertRefuge(10, manager.getId());

        JsonNode session = requestUpload(token, JPEG.length, "REFUGE", refugeId);
        assertThat(postToStorage(session, Map.of(), JPEG).status()).isEqualTo(204);
        assertThat(json(confirm(token, session).body()).get("status").asString()).isEqualTo("READY");

        HttpResult attached = call(HttpMethod.GET,
                "/api/v1/media/attached?entityType=REFUGE&entityId=" + refugeId, obtainToken(Role.HIKER), null);
        assertThat(attached.status()).isEqualTo(200);
        assertThat(json(attached.body())).hasSize(1);
    }

    /**
     * Solo il gestore di un rifugio può caricarvi foto: un escursionista (ruolo sbagliato) e un altro gestore
     * (rifugio non suo) devono ricevere 403.
     */
    @Test
    void onlyTheRefugeManagerUploadsPhotosOnARefuge() {
        UUID refugeId = insertRefuge(10, createUser(Role.PARK_MANAGER).getId());
        String body = """
                {"originalFilename":"IMG_0001.jpg","contentType":"image/jpeg","sizeBytes":%d,"entityType":"REFUGE","entityId":"%s"}
                """.formatted(JPEG.length, refugeId);

        assertThat(call(HttpMethod.POST, "/api/v1/media/upload-requests", obtainToken(Role.HIKER), body).status()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/v1/media/upload-requests", obtainToken(Role.PARK_MANAGER), body).status()).isEqualTo(403);
    }

    /**
     * La policy limita la dimensione a quella dichiarata: un file più grande viene rifiutato da MinIO con 400
     * {@code EntityTooLarge}. Senza oggetto caricato la conferma dà 422 e l'app deve chiedere un nuovo
     * caricamento.
     */
    @Test
    void storageRejectsFileLargerThanDeclared() {
        String hiker = obtainToken(Role.HIKER);
        JsonNode session = requestUpload(hiker, 1024, "HAZARD_REPORT", createHazard(hiker));

        Upload upload = postToStorage(session, Map.of(), JPEG);
        assertThat(upload.status()).isEqualTo(400);
        assertThat(upload.body()).contains("EntityTooLarge");

        assertThat(confirm(hiker, session).status()).isEqualTo(422);
    }

    /**
     * La policy è firmata: modificare il Content-Type o la chiave dell'oggetto rispetto a quelli concessi fa
     * rifiutare il caricamento da MinIO con 403.
     */
    @Test
    void storageRejectsTamperedContentTypeOrKey() {
        String hiker = obtainToken(Role.HIKER);
        JsonNode session = requestUpload(hiker, JPEG.length, "HAZARD_REPORT", createHazard(hiker));

        assertThat(postToStorage(session, Map.of("Content-Type", "image/png"), JPEG).status()).isEqualTo(403);
        assertThat(postToStorage(session, Map.of("key", "2026/01/other.jpg"), JPEG).status()).isEqualTo(403);
    }

    /**
     * Un file di testo dichiarato come JPEG passa da MinIO ma alla conferma la firma binaria non corrisponde:
     * il file va in {@code QUARANTINED} e vengono cancellati sia lo staging sia l'oggetto finale. Una seconda
     * conferma restituisce lo stesso esito.
     */
    @Test
    void fileThatIsNotTheDeclaredImageIsQuarantinedAndDeleted() {
        String hiker = obtainToken(Role.HIKER);
        byte[] text = "not a jpeg, just text pretending to be one".getBytes(StandardCharsets.UTF_8);
        JsonNode session = requestUpload(hiker, text.length, "HAZARD_REPORT", createHazard(hiker));

        assertThat(postToStorage(session, Map.of(), text).status()).isEqualTo(204);
        assertThat(json(confirm(hiker, session).body()).get("status").asString()).isEqualTo("QUARANTINED");

        // Non devono esistere né la copia di staging né l'oggetto finale.
        String stagingKey = session.get("formFields").get("key").asString();
        assertThat(stagingKey).startsWith(STAGING);
        for (String key : new String[]{stagingKey, stagingKey.substring(STAGING.length())}) {
            assertThatThrownBy(() -> admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build()))
                    .isInstanceOf(ErrorResponseException.class);
        }
        assertThat(json(confirm(hiker, session).body()).get("status").asString()).isEqualTo("QUARANTINED");
    }

    /**
     * La policy resta valida per 10 minuti, quindi può essere riusata anche dopo la conferma; però scrive solo
     * sulla chiave di staging. Un secondo caricamento con contenuto diverso non deve cambiare l'oggetto finale
     * già verificato (lo staging lo cancella poi la regola di lifecycle del bucket), e una nuova conferma deve
     * restituire l'esito già registrato senza rileggere lo staging.
     */
    @Test
    void policyReusedAfterConfirmCannotReplaceTheVerifiedObject() throws Exception {
        String hiker = obtainToken(Role.HIKER);
        JsonNode session = requestUpload(hiker, JPEG.length, "HAZARD_REPORT", createHazard(hiker));
        assertThat(postToStorage(session, Map.of(), JPEG).status()).isEqualTo(204);
        assertThat(json(confirm(hiker, session).body()).get("status").asString()).isEqualTo("READY");

        byte[] replacement = "<script>not the verified photo</script>".getBytes(StandardCharsets.UTF_8);
        assertThat(postToStorage(session, Map.of(), replacement).status()).isEqualTo(204);

        String finalKey = session.get("formFields").get("key").asString().substring(STAGING.length());
        try (var stored = admin.getObject(GetObjectArgs.builder().bucket(BUCKET).object(finalKey).build())) {
            assertThat(stored.readAllBytes()).isEqualTo(JPEG);
        }
        JsonNode media = json(confirm(hiker, session).body());
        assertThat(media.get("status").asString()).isEqualTo("READY");
        assertThat(media.get("sizeBytes").asLong()).isEqualTo(JPEG.length);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Foto profilo: file allegati a un utente (target USER)
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Una nuova foto profilo sostituisce la precedente, che viene cancellata sia dal database sia da MinIO.
     * La foto è privata: un altro utente non la legge, non ottiene l'URL di download e non la vede tra gli
     * allegati (sempre 404), mentre il proprietario sì.
     */
    @Test
    void newAvatarReplacesThePreviousOneAndOnlyTheOwnerSeesIt() {
        var hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);

        JsonNode first = uploadAvatar(token, hiker.getId());
        assertThat(avatarOf(token)).isEqualTo(mediaId(first));

        JsonNode second = uploadAvatar(token, hiker.getId());
        assertThat(avatarOf(token)).isEqualTo(mediaId(second));
        // La prima foto non esiste più: né la riga nel database né l'oggetto su MinIO.
        assertThat(call(HttpMethod.GET, "/api/v1/media/" + mediaId(first), token, null).status()).isEqualTo(404);
        assertObjectGone(finalKey(first));

        String other = obtainToken(Role.HIKER);
        assertThat(call(HttpMethod.GET, "/api/v1/media/" + mediaId(second), other, null).status()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/api/v1/media/" + mediaId(second) + "/download-url", other, null).status()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/api/v1/media/attached?entityType=USER&entityId=" + hiker.getId(), other, null).status())
                .isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/api/v1/media/" + mediaId(second) + "/download-url", token, null).status()).isEqualTo(200);
    }

    /** Non si può caricare la foto profilo di un altro utente: la richiesta dà 404, come se l'utente non esistesse. */
    @Test
    void avatarOfAnotherUserIsNotFound() {
        UUID someoneElse = createUser(Role.HIKER).getId();
        HttpResult result = call(HttpMethod.POST, "/api/v1/media/upload-requests", obtainToken(Role.HIKER), """
                {"originalFilename":"me.jpg","contentType":"image/jpeg","sizeBytes":%d,"entityType":"USER","entityId":"%s"}
                """.formatted(JPEG.length, someoneElse));
        assertThat(result.status()).isEqualTo(404);
    }

    /**
     * Cancellando la foto profilo, il profilo non ne ha più una e l'oggetto sparisce da MinIO; cancellando
     * l'account, sparisce anche la foto rimasta.
     */
    @Test
    void deletedAvatarAndDeletedAccountLeaveNothingBehind() {
        var hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);

        JsonNode removed = uploadAvatar(token, hiker.getId());
        assertThat(call(HttpMethod.DELETE, "/api/v1/media/" + mediaId(removed), token, null).status()).isEqualTo(204);
        assertThat(avatarOf(token)).isNull();
        assertObjectGone(finalKey(removed));

        JsonNode kept = uploadAvatar(token, hiker.getId());
        assertThat(call(HttpMethod.DELETE, "/api/v1/users/me", token, null).status()).isEqualTo(204);
        assertObjectGone(finalKey(kept));
    }

    /**
     * Due conferme di foto profilo in parallelo riescono entrambe, ma il lock per utente le esegue una dopo
     * l'altra: alla fine resta una sola foto {@code READY}, ed è quella indicata nel profilo.
     */
    @RepeatedTest(3)
    void concurrentConfirmsLeaveASingleAvatar() throws Exception {
        var hiker = createUser(Role.HIKER);
        String token = tokenFor(hiker);
        JsonNode[] sessions = {
                requestUpload(token, JPEG.length, "USER", hiker.getId()),
                requestUpload(token, JPEG.length, "USER", hiker.getId())
        };
        for (JsonNode session : sessions) {
            assertThat(postToStorage(session, Map.of(), JPEG).status()).isEqualTo(204);
        }

        assertThat(runConcurrently(2, i -> confirm(token, sessions[i]).status())).containsOnly(200);
        Integer ready = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_files WHERE entity_type = 'USER' AND entity_id = ? AND status = 'READY'",
                Integer.class, hiker.getId());
        assertThat(ready).isEqualTo(1);
        assertThat(avatarOf(token)).isIn(mediaId(sessions[0]), mediaId(sessions[1]));
    }

    /**
     * Esegue l'intero caricamento di una foto profilo e controlla che arrivi a {@code READY}.
     *
     * @param token  l'access token dell'utente
     * @param userId l'id dell'utente
     * @return la sessione di caricamento restituita dal backend
     */
    private JsonNode uploadAvatar(String token, UUID userId) {
        JsonNode session = requestUpload(token, JPEG.length, "USER", userId);
        assertThat(postToStorage(session, Map.of(), JPEG).status()).isEqualTo(204);
        assertThat(json(confirm(token, session).body()).get("status").asString()).isEqualTo("READY");
        return session;
    }

    /**
     * Legge dal profilo l'id della foto profilo.
     *
     * @param token l'access token dell'utente
     * @return l'id del file, oppure {@code null} se l'utente non ha una foto
     */
    private String avatarOf(String token) {
        JsonNode avatar = json(call(HttpMethod.GET, "/api/v1/users/me", token, null).body()).get("avatarMediaId");
        return avatar == null || avatar.isNull() ? null : avatar.asString();
    }

    /**
     * Ricava la chiave finale dell'oggetto togliendo il prefisso di staging.
     *
     * @param session la sessione di caricamento
     * @return la chiave dell'oggetto verificato
     */
    private static String finalKey(JsonNode session) {
        return session.get("formFields").get("key").asString().substring(STAGING.length());
    }

    /**
     * Controlla che un oggetto non esista più su MinIO.
     *
     * @param key la chiave dell'oggetto
     */
    private void assertObjectGone(String key) {
        assertThatThrownBy(() -> admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build()))
                .isInstanceOf(ErrorResponseException.class);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Utilità per il caricamento
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Risposta di MinIO a un caricamento.
     *
     * @param status lo stato HTTP
     * @param body   il corpo, che in caso di errore contiene il codice XML di MinIO
     */
    private record Upload(int status, String body) {
    }

    /**
     * Crea una segnalazione di albero caduto.
     *
     * @param token l'access token di chi segnala
     * @return l'id della segnalazione
     */
    private UUID createHazard(String token) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/hazards", token, """
                {"type":"FALLEN_TREE","severity":"MEDIUM","description":"Albero sul sentiero","location":{"lat":39.30,"lon":16.40}}
                """);
        assertThat(result.status()).isEqualTo(201);
        return UUID.fromString(json(result.body()).get("id").asString());
    }

    /**
     * Chiede al backend una sessione di caricamento per un JPEG.
     *
     * @param token      l'access token dell'utente
     * @param size       la dimensione dichiarata in byte
     * @param entityType il tipo di risorsa a cui allegare il file, ad esempio {@code HAZARD_REPORT}
     * @param entityId   l'id della risorsa
     * @return la sessione, con {@code mediaId}, {@code uploadUrl} e {@code formFields} della policy
     */
    private JsonNode requestUpload(String token, long size, String entityType, UUID entityId) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/media/upload-requests", token, """
                {"originalFilename":"IMG_0001.jpg","contentType":"image/jpeg","sizeBytes":%d,"entityType":"%s","entityId":"%s"}
                """.formatted(size, entityType, entityId));
        assertThat(result.status()).isEqualTo(201);
        return json(result.body());
    }

    /**
     * Chiede al backend di confermare un caricamento.
     *
     * @param token   l'access token dell'utente
     * @param session la sessione di caricamento
     * @return la risposta del server
     */
    private HttpResult confirm(String token, JsonNode session) {
        return call(HttpMethod.POST, "/api/v1/media/" + mediaId(session) + "/confirm", token, null);
    }

    /**
     * Legge l'id del file dalla sessione di caricamento.
     *
     * @param session la sessione
     * @return l'id del file
     */
    private static String mediaId(JsonNode session) {
        return session.get("mediaId").asString();
    }

    /**
     * Carica un file su MinIO come farebbe l'app: tutti i campi della policy (eventualmente sostituiti da
     * valori manomessi), poi la parte "file" per ultima, con lo stesso Content-Type dichiarato.
     *
     * @param session  la sessione di caricamento
     * @param tampered campi da sovrascrivere per simulare una manomissione (vuoto per un caricamento normale)
     * @param file     il contenuto del file
     * @return la risposta di MinIO
     */
    private Upload postToStorage(JsonNode session, Map<String, String> tampered, byte[] file) {
        Map<String, String> fields = new LinkedHashMap<>();
        session.get("formFields").properties().forEach(e -> fields.put(e.getKey(), e.getValue().asString()));
        fields.putAll(tampered);

        MultipartBodyBuilder form = new MultipartBodyBuilder();
        fields.forEach(form::part);
        form.part("file", file).filename("IMG_0001.jpg").contentType(MediaType.IMAGE_JPEG);

        return storageClient().post()
                .uri(URI.create(session.get("uploadUrl").asString()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form.build())
                .exchange((req, res) -> new Upload(res.getStatusCode().value(),
                        new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    /**
     * Crea il client HTTP verso MinIO. Il corpo viene bufferizzato per mandare l'header Content-Length: MinIO
     * rifiuta un POST con policy inviato a blocchi (chunked) con l'errore EmptyRequestBody.
     *
     * @return il client
     */
    private static RestClient storageClient() {
        return RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(new JdkClientHttpRequestFactory()))
                .build();
    }

    /**
     * Costruisce un finto JPEG: la firma FF D8 FF E0 seguita da byte di riempimento.
     *
     * @param size la dimensione totale in byte
     * @return il contenuto del file
     */
    private static byte[] jpeg(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 0x42);
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xE0;
        return bytes;
    }
}
