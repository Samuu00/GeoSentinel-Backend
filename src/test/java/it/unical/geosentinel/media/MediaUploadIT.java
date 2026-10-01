package it.unical.geosentinel.media;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
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

// Contratto dell'upload per l'app (M7.5): presigned POST verso un MinIO vero, poi confirm e download firmato.
// Il multipart lo costruisce MultipartBodyBuilder come farebbe Ktor (submitFormWithBinaryData): formFields, poi "file"
class MediaUploadIT extends AbstractIntegrationTest {

    private static final String ACCESS_KEY = "test-access-key";
    private static final String SECRET_KEY = "test-secret-key";
    private static final String BUCKET = "geosentinel-media";
    private static final byte[] JPEG = jpeg(2048);

    // Stessa immagine del compose (le ufficiali minio/minio non si scaricano più)
    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z"))
                    .withCommand("server", "/data")
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    static {
        MINIO.start();
    }

    // endpoint e publicEndpoint coincidono: il test fa sia l'app sia il telefono
    @DynamicPropertySource
    static void minioProperties(DynamicPropertyRegistry registry) {
        registry.add("geosentinel.storage.minio.endpoint", MediaUploadIT::minioUrl);
        registry.add("geosentinel.storage.minio.public-endpoint", MediaUploadIT::minioUrl);
        registry.add("geosentinel.storage.minio.bucket", () -> BUCKET);
    }

    private static String minioUrl() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private final MinioClient admin = MinioClient.builder()
            .endpoint(minioUrl()).region("us-east-1").credentials(ACCESS_KEY, SECRET_KEY).build();

    // Nel compose il bucket lo crea minio-init: qui lo fa il test
    @BeforeEach
    void createBucket() throws Exception {
        if (!admin.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

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

        // Un altro escursionista vede la foto nella segnalazione e la scarica
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

    @Test
    void storageRejectsFileLargerThanDeclared() {
        String hiker = obtainToken(Role.HIKER);
        JsonNode session = requestUpload(hiker, 1024, "HAZARD_REPORT", createHazard(hiker));

        Upload upload = postToStorage(session, Map.of(), JPEG);
        assertThat(upload.status()).isEqualTo(400);
        assertThat(upload.body()).contains("EntityTooLarge");

        // Nessun oggetto: il confirm risponde 422 e l'app chiede un nuovo upload
        assertThat(confirm(hiker, session).status()).isEqualTo(422);
    }

    @Test
    void storageRejectsTamperedContentTypeOrKey() {
        String hiker = obtainToken(Role.HIKER);
        JsonNode session = requestUpload(hiker, JPEG.length, "HAZARD_REPORT", createHazard(hiker));

        assertThat(postToStorage(session, Map.of("Content-Type", "image/png"), JPEG).status()).isEqualTo(403);
        assertThat(postToStorage(session, Map.of("key", "2026/01/other.jpg"), JPEG).status()).isEqualTo(403);
    }

    @Test
    void fileThatIsNotTheDeclaredImageIsQuarantinedAndDeleted() {
        String hiker = obtainToken(Role.HIKER);
        byte[] text = "not a jpeg, just text pretending to be one".getBytes(StandardCharsets.UTF_8);
        JsonNode session = requestUpload(hiker, text.length, "HAZARD_REPORT", createHazard(hiker));

        assertThat(postToStorage(session, Map.of(), text).status()).isEqualTo(204);
        assertThat(json(confirm(hiker, session).body()).get("status").asString()).isEqualTo("QUARANTINED");

        String key = session.get("formFields").get("key").asString();
        assertThatThrownBy(() -> admin.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build()))
                .isInstanceOf(ErrorResponseException.class);
        // Un retry del confirm restituisce lo stesso esito
        assertThat(json(confirm(hiker, session).body()).get("status").asString()).isEqualTo("QUARANTINED");
    }

    // ------------------------------------------------------------------------------------------------------------

    private record Upload(int status, String body) {
    }

    private UUID createHazard(String token) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/hazards", token, """
                {"type":"FALLEN_TREE","severity":"MEDIUM","description":"Albero sul sentiero","location":{"lat":39.30,"lon":16.40}}
                """);
        assertThat(result.status()).isEqualTo(201);
        return UUID.fromString(json(result.body()).get("id").asString());
    }

    private JsonNode requestUpload(String token, long size, String entityType, UUID entityId) {
        HttpResult result = call(HttpMethod.POST, "/api/v1/media/upload-requests", token, """
                {"originalFilename":"IMG_0001.jpg","contentType":"image/jpeg","sizeBytes":%d,"entityType":"%s","entityId":"%s"}
                """.formatted(size, entityType, entityId));
        assertThat(result.status()).isEqualTo(201);
        return json(result.body());
    }

    private HttpResult confirm(String token, JsonNode session) {
        return call(HttpMethod.POST, "/api/v1/media/" + mediaId(session) + "/confirm", token, null);
    }

    private static String mediaId(JsonNode session) {
        return session.get("mediaId").asString();
    }

    // Come l'app: tutti i formFields (qui con eventuali campi manomessi), poi "file" per ultimo,
    // con Content-Type della parte uguale a quello dichiarato
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

    // Buffering: MinIO rifiuta un POST policy chunked (EmptyRequestBody), serve il Content-Length
    private static RestClient storageClient() {
        return RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(new JdkClientHttpRequestFactory()))
                .build();
    }

    // Firma JPEG (FF D8 FF E0) seguita da byte di riempimento
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
