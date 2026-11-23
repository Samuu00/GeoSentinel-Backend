// Test di integrazione del ciclo di vita dei token di autenticazione, con chiamate HTTP vere e PostGIS e
// Redis in Testcontainers: registrazione, logout con blacklist dell'access token, rotazione dei refresh token
// con rilevamento del furto, periodo di grazia per le risposte perse, scadenza assoluta della sessione e
// gerarchia dei ruoli. Ogni test fa poche chiamate alle rotte di autenticazione, sotto il limite per IP, che
// comunque viene azzerato prima di ogni test.
package it.unical.geosentinel.identity;

import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica il comportamento di access token e refresh token nei casi normali e in quelli di attacco.
 */
class AuthFlowIT extends AbstractIntegrationTest {

    /** Rotta qualsiasi che richiede l'autenticazione, usata per provare la validità di un access token. */
    private static final String PROTECTED = "/api/v1/bookings/my";

    /** Encoder dei JWT dell'applicazione, usato per costruire a mano un token già scaduto. */
    @Autowired
    private JwtEncoder jwtEncoder;

    /**
     * Dopo il logout l'access token, ancora formalmente non scaduto, deve essere rifiutato con 401 perché è
     * finito nella blacklist.
     */
    @Test
    void logoutBlacklistsTheAccessToken() {
        JsonNode tokens = register();
        String accessToken = tokens.get("accessToken").asString();
        assertThat(call(HttpMethod.GET, PROTECTED, accessToken, null).status()).isEqualTo(200);

        HttpResult logout = call(HttpMethod.POST, "/api/v1/auth/logout", accessToken,
                "{\"refreshToken\":\"%s\"}".formatted(tokens.get("refreshToken").asString()));
        assertThat(logout.status()).isEqualTo(204);

        HttpResult after = call(HttpMethod.GET, PROTECTED, accessToken, null);
        assertThat(after.status()).isEqualTo(401);
        assertThat(isProblemJson(after)).isTrue();
    }

    /**
     * Ogni refresh token vale una volta sola. Se si riusa un token il cui successore è già stato a sua volta
     * usato, non c'è periodo di grazia: è un furto, e l'intera famiglia di token viene revocata, compreso
     * l'ultimo token emesso.
     */
    @Test
    void reusingARefreshTokenAfterItsSuccessorWasUsedRevokesTheWholeFamily() {
        String first = register().get("refreshToken").asString();
        String second = rotate(first);
        String third = rotate(second);

        assertThat(refresh(first).status()).isEqualTo(401);
        assertThat(refresh(third).status()).isEqualTo(401);
    }

    /**
     * Se la risposta di una rotazione si perde (per esempio la rete cade), il client riprova con il vecchio
     * token. Entro il periodo di grazia questo deve funzionare e restituire un token valido.
     */
    @Test
    void aLostRotationResponseIsRecoveredWithinTheGracePeriod() {
        String first = register().get("refreshToken").asString();
        // Rotazione la cui risposta, nello scenario, non arriva mai al client.
        rotate(first);

        String recovered = rotate(first);

        assertThat(refresh(recovered).status()).isEqualTo(200);
    }

    /**
     * Un ladro usa il vecchio token durante il periodo di grazia e ottiene un suo successore, che rende non
     * più valido quello legittimo. Quando il proprietario usa il proprio successore, il riuso viene rilevato e
     * l'intera famiglia revocata: muore anche il token del ladro.
     */
    @Test
    void theftWithinTheGracePeriodIsDetectedWhenTheLegitimateSuccessorIsUsed() {
        String first = register().get("refreshToken").asString();
        String legitimate = rotate(first);
        String stolen = rotate(first);

        assertThat(refresh(legitimate).status()).isEqualTo(401);
        assertThat(refresh(stolen).status()).isEqualTo(401);
    }

    /**
     * Finito il periodo di grazia (qui simulato riportando indietro di due minuti l'istante di revoca), usare
     * il vecchio token è trattato come furto: vengono rifiutati sia il vecchio sia il successore.
     */
    @Test
    void outsideTheGracePeriodTheOldTokenIsTreatedAsTheft() {
        String first = register().get("refreshToken").asString();
        String second = rotate(first);
        jdbcTemplate.update("UPDATE refresh_tokens SET revoked_at = now() - interval '2 minutes' WHERE replaced_by_id IS NOT NULL");

        assertThat(refresh(first).status()).isEqualTo(401);
        assertThat(refresh(second).status()).isEqualTo(401);
    }

    /**
     * Dopo un logout, il vecchio token resta rifiutato anche se è ancora nel periodo di grazia: il suo
     * successore è stato revocato dal logout, quindi la sessione resta chiusa.
     */
    @Test
    void afterALogoutTheRotatedTokenGetsNoGrace() {
        String first = register().get("refreshToken").asString();
        HttpResult rotated = refresh(first);
        JsonNode tokens = json(rotated.body());
        HttpResult logout = call(HttpMethod.POST, "/api/v1/auth/logout", tokens.get("accessToken").asString(),
                "{\"refreshToken\":\"%s\"}".formatted(tokens.get("refreshToken").asString()));
        assertThat(logout.status()).isEqualTo(204);

        assertThat(refresh(first).status()).isEqualTo(401);
    }

    /**
     * Ogni sessione ha una scadenza assoluta, indipendente dalle rotazioni: superata quella, anche un refresh
     * token mai usato deve essere rifiutato.
     */
    @Test
    void refreshAfterTheAbsoluteSessionExpiryIsRejectedEvenForAnUnusedToken() {
        String refreshToken = register().get("refreshToken").asString();
        jdbcTemplate.update("UPDATE refresh_tokens SET family_expires_at = now() - interval '1 minute'");

        assertThat(refresh(refreshToken).status()).isEqualTo(401);
    }

    /**
     * Una password di più di 72 byte deve essere fermata dalla validazione con 400 sul campo
     * {@code password}, invece di arrivare a BCrypt e provocare un errore 500.
     */
    @Test
    void passwordLongerThan72BytesIsA400NotA500() {
        HttpResult result = call(HttpMethod.POST, "/api/v1/auth/register", null, """
                {"email":"lunga@test.dev","password":"%s","displayName":"Password lunga","birthDate":"1990-01-01"}
                """.formatted("a".repeat(80)));

        assertThat(result.status()).isEqualTo(400);
        assertThat(json(result.body()).get("errors").get(0).get("field").asString()).isEqualTo("password");
    }

    /**
     * Un access token scaduto da appena 2 secondi deve essere rifiutato. Con la tolleranza di default sugli
     * orologi (60 secondi) verrebbe accettato, e un token revocato tornerebbe valido dopo la scadenza, quando
     * la sua voce nella blacklist su Redis è già stata cancellata.
     */
    @Test
    void expiredAccessTokenIsRejected() {
        UUID userId = createUser(Role.HIKER).getId();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://geosentinel.dev")
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now.minus(Duration.ofMinutes(15)))
                .expiresAt(now.minusSeconds(2))
                .claim("roles", List.of("HIKER"))
                .build();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        assertThat(call(HttpMethod.GET, PROTECTED, expired, null).status()).isEqualTo(401);
    }

    /**
     * Un escursionista non può creare un rifugio (403), mentre un amministratore sì (201): la gerarchia dei
     * ruoli, in cui ADMIN include PARK_MANAGER, vale anche per i controlli {@code @PreAuthorize}.
     */
    @Test
    void hikerCannotUseAParkManagerRouteButAdminCanThroughTheRoleHierarchy() {
        String refuge = """
                {"name":"Rifugio Test","location":{"lat":39.28,"lon":16.45},"capacity":10}
                """;

        HttpResult asHiker = call(HttpMethod.POST, "/api/v1/refuges", obtainToken(Role.HIKER), refuge);
        assertThat(asHiker.status()).isEqualTo(403);
        assertThat(isProblemJson(asHiker)).isTrue();

        assertThat(call(HttpMethod.POST, "/api/v1/refuges", obtainToken(Role.ADMIN), refuge).status()).isEqualTo(201);
    }

    /**
     * Registra un nuovo escursionista con un'email casuale.
     *
     * @return il corpo della risposta, con access token e refresh token
     */
    private JsonNode register() {
        HttpResult result = call(HttpMethod.POST, "/api/v1/auth/register", null, """
                {"email":"%s@test.dev","password":"%s","displayName":"Escursionista","birthDate":"1990-01-01"}
                """.formatted(UUID.randomUUID(), TEST_PASSWORD));
        assertThat(result.status()).isEqualTo(201);
        return json(result.body());
    }

    /**
     * Chiede un nuovo paio di token con un refresh token.
     *
     * @param refreshToken il refresh token da usare
     * @return la risposta del server
     */
    private HttpResult refresh(String refreshToken) {
        return call(HttpMethod.POST, "/api/v1/auth/refresh", null, "{\"refreshToken\":\"%s\"}".formatted(refreshToken));
    }

    /**
     * Esegue una rotazione e controlla che riesca.
     *
     * @param refreshToken il refresh token da ruotare
     * @return il nuovo refresh token
     */
    private String rotate(String refreshToken) {
        HttpResult result = refresh(refreshToken);
        assertThat(result.status()).isEqualTo(200);
        return json(result.body()).get("refreshToken").asString();
    }
}
