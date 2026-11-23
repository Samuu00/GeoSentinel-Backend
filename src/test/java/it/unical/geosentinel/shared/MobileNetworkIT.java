// Test di integrazione delle ottimizzazioni per la rete mobile, con chiamate HTTP vere e PostGIS in
// Testcontainers: compressione gzip delle risposte, GET condizionali con ETag sul catalogo pubblico (per non
// riscaricare dati che l'app ha già) e liste paginate. In montagna la connessione è lenta e spesso a consumo,
// quindi ogni byte risparmiato conta.
package it.unical.geosentinel.shared;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica compressione, intestazioni di cache, ETag e paginazione delle risposte.
 */
class MobileNetworkIT extends AbstractIntegrationTest {

    /**
     * Client HTTP del JDK usato al posto di quello della classe base: non decomprime da solo, quindi il test
     * vede l'header Content-Encoding e i byte effettivamente trasmessi.
     */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Il dettaglio di un sentiero lungo supera i 10 KB. Senza Accept-Encoding arriva non compresso; con
     * {@code Accept-Encoding: gzip} deve arrivare compresso, occupare meno della metà e, una volta
     * decompresso, essere identico alla versione non compressa.
     */
    @Test
    void largeJsonResponseIsGzippedWhenTheClientAcceptsIt() throws Exception {
        UUID trailId = insertTrail("Sentiero lungo", longLineString(600));

        HttpResponse<byte[]> plain = get("/api/v1/trails/" + trailId, Map.of());
        HttpResponse<byte[]> gzipped = get("/api/v1/trails/" + trailId, Map.of(HttpHeaders.ACCEPT_ENCODING, "gzip"));

        assertThat(plain.statusCode()).isEqualTo(200);
        assertThat(plain.body().length).isGreaterThan(10_000);
        assertThat(plain.headers().firstValue(HttpHeaders.CONTENT_ENCODING)).isEmpty();

        assertThat(gzipped.statusCode()).isEqualTo(200);
        assertThat(gzipped.headers().firstValue(HttpHeaders.CONTENT_ENCODING)).hasValue("gzip");
        assertThat(gzipped.body().length).isLessThan(plain.body().length / 2);
        assertThat(gunzip(gzipped.body())).isEqualTo(new String(plain.body(), StandardCharsets.UTF_8));
    }

    /**
     * La prima lettura di un sentiero restituisce un ETag debole e {@code Cache-Control: no-cache}. Una
     * seconda lettura con {@code If-None-Match} uguale all'ETag deve dare 304 senza corpo.
     */
    @Test
    void secondTrailGetWithIfNoneMatchIs304() throws Exception {
        UUID trailId = insertTrail("Sentiero", longLineString(5));

        HttpResponse<byte[]> first = get("/api/v1/trails/" + trailId, Map.of());
        String etag = first.headers().firstValue(HttpHeaders.ETAG).orElseThrow();
        assertThat(etag).startsWith("W/\"");
        // Con no-cache l'app può conservare la risposta ma deve sempre farla rivalidare al server. Il valore
        // di default (no-store) vieterebbe di conservarla e renderebbe inutile l'ETag.
        assertThat(first.headers().firstValue(HttpHeaders.CACHE_CONTROL)).hasValue("no-cache");

        HttpResponse<byte[]> second = get("/api/v1/trails/" + trailId, Map.of(HttpHeaders.IF_NONE_MATCH, etag));

        assertThat(second.statusCode()).isEqualTo(304);
        assertThat(second.body()).isEmpty();
    }

    /**
     * L'app manda sempre Accept-Encoding: l'ETag ricevuto con una risposta compressa deve quindi valere anche
     * per la richiesta successiva compressa, che deve dare 304.
     */
    @Test
    void etagOfAGzippedResponseStillGives304() throws Exception {
        UUID trailId = insertTrail("Sentiero lungo", longLineString(600));
        Map<String, String> gzip = Map.of(HttpHeaders.ACCEPT_ENCODING, "gzip");

        HttpResponse<byte[]> first = get("/api/v1/trails/" + trailId, gzip);
        assertThat(first.headers().firstValue(HttpHeaders.CONTENT_ENCODING)).hasValue("gzip");
        String etag = first.headers().firstValue(HttpHeaders.ETAG).orElseThrow();

        HttpResponse<byte[]> second = get("/api/v1/trails/" + trailId,
                Map.of(HttpHeaders.ACCEPT_ENCODING, "gzip", HttpHeaders.IF_NONE_MATCH, etag));

        assertThat(second.statusCode()).isEqualTo(304);
    }

    /**
     * Dopo che il gestore chiude il sentiero, una lettura con il vecchio ETag deve dare 200 con il dettaglio
     * aggiornato e un ETag diverso.
     */
    @Test
    void changedTrailGetsANewEtag() throws Exception {
        UUID trailId = insertTrail("Sentiero", longLineString(5));
        String etag = get("/api/v1/trails/" + trailId, Map.of()).headers().firstValue(HttpHeaders.ETAG).orElseThrow();

        String token = obtainToken(Role.PARK_MANAGER);
        HttpResult closed = call(HttpMethod.PATCH, "/api/v1/trails/" + trailId + "/status", token, """
                {"status":"CLOSED"}
                """);
        assertThat(closed.status()).isEqualTo(200);

        HttpResponse<byte[]> after = get("/api/v1/trails/" + trailId, Map.of(HttpHeaders.IF_NONE_MATCH, etag));
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(after.headers().firstValue(HttpHeaders.ETAG).orElseThrow()).isNotEqualTo(etag);
    }

    /**
     * Le rotte con dati personali (qui le proprie prenotazioni) devono restare {@code no-store} e senza ETag,
     * così le risposte non vengono conservate in nessuna cache.
     */
    @Test
    void personalRoutesStayNoStore() {
        HttpResult bookings = call(HttpMethod.GET, "/api/v1/bookings/my", obtainToken(Role.HIKER), null);

        assertThat(bookings.status()).isEqualTo(200);
        assertThat(bookings.headers().getCacheControl()).contains("no-store");
        assertThat(bookings.headers().getETag()).isNull();
    }

    /**
     * Con tre prenotazioni e pagine da due, la prima pagina deve contenere le due con l'arrivo più lontano,
     * in ordine decrescente, e riportare il totale di elementi (3) e di pagine (2).
     */
    @Test
    void myBookingsArePagedAndSortedByCheckInDescending() {
        UUID refugeId = insertRefuge(10, createUser(Role.PARK_MANAGER).getId());
        String token = obtainToken(Role.HIKER);
        LocalDate base = LocalDate.now().plusDays(10);
        for (int i = 0; i < 3; i++) {
            HttpResult booked = call(HttpMethod.POST, "/api/v1/bookings", token, """
                    {"refugeId":"%s","checkIn":"%s","checkOut":"%s","guests":1}
                    """.formatted(refugeId, base.plusDays(i * 2L), base.plusDays(i * 2L + 1)),
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertThat(booked.status()).isEqualTo(201);
        }

        HttpResult page = call(HttpMethod.GET, "/api/v1/bookings/my?size=2", token, null);

        assertThat(page.status()).isEqualTo(200);
        JsonNode body = json(page.body());
        assertThat(body.get("content")).hasSize(2);
        assertThat(body.get("content").get(0).get("checkIn").asString()).isEqualTo(base.plusDays(4).toString());
        assertThat(body.get("content").get(1).get("checkIn").asString()).isEqualTo(base.plusDays(2).toString());
        assertThat(body.get("page").get("totalElements").asLong()).isEqualTo(3);
        assertThat(body.get("page").get("totalPages").asLong()).isEqualTo(2);
    }

    /**
     * L'ordinamento delle proprie prenotazioni è ammesso solo sui campi della lista bianca: un campo non
     * previsto, un campo di un'entità collegata o l'id dell'utente devono dare 400 con un corpo ProblemDetail.
     */
    @Test
    void myBookingsRejectSortOutsideTheWhitelist() {
        String token = obtainToken(Role.HIKER);

        for (String sort : new String[]{"status", "refuge.name", "userId"}) {
            HttpResult result = call(HttpMethod.GET, "/api/v1/bookings/my?sort=" + sort, token, null);
            assertThat(result.status()).as(sort).isEqualTo(400);
            assertThat(isProblemJson(result)).isTrue();
        }
    }

    /**
     * Esegue una GET anonima con il client del JDK, senza decompressione automatica.
     *
     * @param path    il percorso della risorsa
     * @param headers gli header da aggiungere alla richiesta
     * @return la risposta con il corpo in byte, così come trasmesso
     * @throws Exception se la richiesta fallisce a livello di rete
     */
    private HttpResponse<byte[]> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET();
        headers.forEach(request::header);
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * Decomprime un corpo gzip.
     *
     * @param bytes i byte compressi
     * @return il testo decompresso in UTF-8
     * @throws Exception se i dati non sono un gzip valido
     */
    private static String gunzip(byte[] bytes) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Costruisce una linea in formato WKT con il numero di punti indicato, in Sila e nell'ordine
     * (longitudine latitudine). Con 600 punti il dettaglio del sentiero supera i 10 KB.
     *
     * @param points il numero di punti
     * @return la linea in WKT
     */
    private static String longLineString(int points) {
        StringJoiner coordinates = new StringJoiner(", ", "LINESTRING(", ")");
        for (int i = 0; i < points; i++) {
            coordinates.add(String.format(Locale.ROOT, "%.6f %.6f", 16.45 + i * 0.0001, 39.28 + i * 0.0001));
        }
        return coordinates.toString();
    }
}
