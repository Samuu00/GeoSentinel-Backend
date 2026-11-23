// Test di integrazione del rate limit, con chiamate HTTP vere e Redis e PostGIS in Testcontainers.
// Il limite per IP è un token bucket salvato in Redis (aggiornato da uno script Lua) con tre politiche e
// bucket separati: autenticazione, refresh e resto dell'API. Accanto c'è un limite per account sui login
// sbagliati. Tutte le richieste del test arrivano dallo stesso IP, come più telefoni dietro il CGNAT di un
// operatore mobile.
package it.unical.geosentinel.identity;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.infrastructure.security.RateLimitProperties;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica che il limite per IP, quello per account e quello per utente autenticato agiscano ciascuno sul
 * proprio bucket.
 */
class RateLimitIT extends AbstractIntegrationTest {

    /** Rotta pubblica della lista dei sentieri in un riquadro, usata come richiesta generica. */
    private static final String TRAILS_BBOX = "/api/v1/trails?bbox=16.4,39.2,16.6,39.4";

    /** Limiti configurati, letti per non scrivere i valori a mano nel test. */
    @Autowired
    private RateLimitProperties limits;

    /**
     * Venti escursionisti diversi sbagliano la password dallo stesso IP (caso tipico del CGNAT): nessuno deve
     * ricevere 429, ognuno riceve il normale 401.
     */
    @Test
    void wrongLoginsFromManyAccountsBehindTheSameIpAreNotLimited() {
        for (int i = 0; i < 20; i++) {
            assertThat(login("utente-" + i + "@test.dev").status()).isEqualTo(401);
        }
    }

    /**
     * Il tentativo di indovinare la password di un account lo ferma il limite per account (10 errori ogni 15
     * minuti), non quello per IP. All'undicesimo errore sullo stesso account si attende 429 con Retry-After e
     * slug {@code rate-limit-exceeded}; un altro account dallo stesso IP non deve risentirne.
     */
    @Test
    void eleventhWrongLoginOnTheSameAccountGets429() {
        for (int i = 0; i < 10; i++) {
            assertThat(login("vittima@test.dev").status()).isEqualTo(401);
        }

        HttpResult blocked = login("vittima@test.dev");

        assertThat(blocked.status()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.headers().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(json(blocked.body()).get("type").asString()).endsWith("/rate-limit-exceeded");
        // Il bucket per IP ha ancora gettoni, quindi il 429 viene dal limite per account.
        assertThat(Long.parseLong(blocked.headers().getFirst("X-RateLimit-Remaining"))).isPositive();
        assertThat(login("altro@test.dev").status()).isEqualTo(401);
    }

    /**
     * Superato il tetto per IP di login e registrazione, la richiesta successiva deve ricevere 429 con
     * Retry-After e un corpo ProblemDetail.
     */
    @Test
    void authRequestsBeyondTheIpLimitGet429() {
        exhaustAuthRateLimit();

        HttpResult blocked = invalidRegister();

        assertThat(blocked.status()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.headers().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(isProblemJson(blocked)).isTrue();
    }

    /**
     * Le chiamate pubbliche usano un bucket diverso: dopo averle fatte, il bucket di autenticazione esaurito
     * deve restare esaurito.
     */
    @Test
    void publicRequestsDoNotRefillTheAuthBucket() {
        exhaustAuthRateLimit();
        for (int i = 0; i < 3; i++) {
            assertThat(call(HttpMethod.GET, TRAILS_BBOX, null, null).status()).isEqualTo(200);
        }

        assertThat(invalidRegister().status()).isEqualTo(429);
    }

    /**
     * Il refresh ha un bucket proprio e più capiente: con il bucket di login esaurito, cinquanta refresh dallo
     * stesso IP devono ricevere il normale 401 (token inesistente) e non 429.
     */
    @Test
    void refreshHasItsOwnBucketLargerThanTheLoginOne() {
        exhaustAuthRateLimit();

        for (int i = 0; i < 50; i++) {
            HttpResult refresh = call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                    "{\"refreshToken\":\"%s\"}".formatted(UUID.randomUUID()));
            assertThat(refresh.status()).isEqualTo(401);
        }
    }

    /**
     * Con il bearer, anche le chiamate pubbliche vengono contate per utente e non per IP. Le richieste del primo
     * utente consumano solo il suo bucket: il secondo utente e un client anonimo dallo stesso IP partono ancora
     * da un bucket pieno.
     */
    @Test
    void authenticatedRequestsAreCountedPerUserNotPerIp() {
        String first = obtainToken(Role.HIKER);
        String second = obtainToken(Role.HIKER);
        long full = limits.generalPerMinute();

        long firstUserAfterOne = remaining(call(HttpMethod.GET, TRAILS_BBOX, first, null));
        long firstUserAfterTwo = remaining(call(HttpMethod.GET, TRAILS_BBOX, first, null));

        assertThat(firstUserAfterOne).isEqualTo(full - 1);
        assertThat(firstUserAfterTwo).isLessThan(firstUserAfterOne);
        assertThat(remaining(call(HttpMethod.GET, TRAILS_BBOX, second, null))).isEqualTo(full - 1);
        assertThat(remaining(call(HttpMethod.GET, TRAILS_BBOX, null, null))).isEqualTo(full - 1);
    }

    /**
     * Invia una registrazione vuota, rifiutata subito con 400 ma conteggiata dal rate limit.
     *
     * @return la risposta del server
     */
    private HttpResult invalidRegister() {
        return call(HttpMethod.POST, "/api/v1/auth/register", null, "{}");
    }

    /**
     * Tenta un login con una password sbagliata.
     *
     * @param email l'email dell'account
     * @return la risposta del server
     */
    private HttpResult login(String email) {
        return call(HttpMethod.POST, "/api/v1/auth/login", null,
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, "x" + TEST_PASSWORD));
    }

    /**
     * Legge quanti gettoni restano nel bucket dopo una risposta 200.
     *
     * @param result la risposta
     * @return il valore dell'header {@code X-RateLimit-Remaining}
     */
    private static long remaining(HttpResult result) {
        assertThat(result.status()).isEqualTo(200);
        return Long.parseLong(result.headers().getFirst("X-RateLimit-Remaining"));
    }
}
