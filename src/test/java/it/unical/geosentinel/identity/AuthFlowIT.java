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

// Ciclo completo dei token. Ogni test resta sotto le 5 chiamate a /auth/** (rate limit per IP),
// che FLUSHALL azzera prima di ogni test
class AuthFlowIT extends AbstractIntegrationTest {

    private static final String PROTECTED = "/api/v1/bookings/my";

    @Autowired
    private JwtEncoder jwtEncoder;

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

    @Test
    void reusingARefreshTokenAfterItsSuccessorWasUsedRevokesTheWholeFamily() {
        String first = register().get("refreshToken").asString();
        String second = rotate(first);
        String third = rotate(second);

        // il successore di first è già stato usato: niente periodo di grazia, è un furto
        assertThat(refresh(first).status()).isEqualTo(401);
        assertThat(refresh(third).status()).isEqualTo(401);
    }

    // ADR-0019: la risposta della rotazione si è persa (rete caduta), il client riprova con il token vecchio
    @Test
    void aLostRotationResponseIsRecoveredWithinTheGracePeriod() {
        String first = register().get("refreshToken").asString();
        rotate(first); // risposta mai arrivata al client

        String recovered = rotate(first);

        assertThat(refresh(recovered).status()).isEqualTo(200);
    }

    @Test
    void theftWithinTheGracePeriodIsDetectedWhenTheLegitimateSuccessorIsUsed() {
        String first = register().get("refreshToken").asString();
        String legitimate = rotate(first);
        String stolen = rotate(first); // un ladro usa first nella finestra: il successore legittimo viene revocato

        assertThat(refresh(legitimate).status()).isEqualTo(401);
        // il riuso del successore revoca la famiglia: anche il token del ladro è morto
        assertThat(refresh(stolen).status()).isEqualTo(401);
    }

    @Test
    void outsideTheGracePeriodTheOldTokenIsTreatedAsTheft() {
        String first = register().get("refreshToken").asString();
        String second = rotate(first);
        jdbcTemplate.update("UPDATE refresh_tokens SET revoked_at = now() - interval '2 minutes' WHERE replaced_by_id IS NOT NULL");

        assertThat(refresh(first).status()).isEqualTo(401);
        assertThat(refresh(second).status()).isEqualTo(401);
    }

    @Test
    void afterALogoutTheRotatedTokenGetsNoGrace() {
        String first = register().get("refreshToken").asString();
        HttpResult rotated = refresh(first);
        JsonNode tokens = json(rotated.body());
        HttpResult logout = call(HttpMethod.POST, "/api/v1/auth/logout", tokens.get("accessToken").asString(),
                "{\"refreshToken\":\"%s\"}".formatted(tokens.get("refreshToken").asString()));
        assertThat(logout.status()).isEqualTo(204);

        // first è nella finestra, ma il suo successore è stato revocato dal logout: la sessione resta chiusa
        assertThat(refresh(first).status()).isEqualTo(401);
    }

    @Test
    void refreshAfterTheAbsoluteSessionExpiryIsRejectedEvenForAnUnusedToken() {
        String refreshToken = register().get("refreshToken").asString();
        jdbcTemplate.update("UPDATE refresh_tokens SET family_expires_at = now() - interval '1 minute'");

        assertThat(refresh(refreshToken).status()).isEqualTo(401);
    }

    @Test
    void passwordLongerThan72BytesIsA400NotA500() {
        HttpResult result = call(HttpMethod.POST, "/api/v1/auth/register", null, """
                {"email":"lunga@test.dev","password":"%s","displayName":"Password lunga","birthDate":"1990-01-01"}
                """.formatted("a".repeat(80)));

        assertThat(result.status()).isEqualTo(400);
        assertThat(json(result.body()).get("errors").get(0).get("field").asString()).isEqualTo("password");
    }

    @Test
    void expiredAccessTokenIsRejected() {
        UUID userId = createUser(Role.HIKER).getId();
        // oltre i 60 s di tolleranza di JwtTimestampValidator
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://geosentinel.dev")
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(past)
                .expiresAt(past.plus(Duration.ofMinutes(5)))
                .claim("roles", List.of("HIKER"))
                .build();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        assertThat(call(HttpMethod.GET, PROTECTED, expired, null).status()).isEqualTo(401);
    }

    @Test
    void hikerCannotUseAParkManagerRouteButAdminCanThroughTheRoleHierarchy() {
        String refuge = """
                {"name":"Rifugio Test","location":{"lat":39.28,"lon":16.45},"capacity":10}
                """;

        HttpResult asHiker = call(HttpMethod.POST, "/api/v1/refuges", obtainToken(Role.HIKER), refuge);
        assertThat(asHiker.status()).isEqualTo(403);
        assertThat(isProblemJson(asHiker)).isTrue();

        // RoleHierarchy: ADMIN > PARK_MANAGER vale anche per @PreAuthorize
        assertThat(call(HttpMethod.POST, "/api/v1/refuges", obtainToken(Role.ADMIN), refuge).status()).isEqualTo(201);
    }

    private JsonNode register() {
        HttpResult result = call(HttpMethod.POST, "/api/v1/auth/register", null, """
                {"email":"%s@test.dev","password":"%s","displayName":"Escursionista","birthDate":"1990-01-01"}
                """.formatted(UUID.randomUUID(), TEST_PASSWORD));
        assertThat(result.status()).isEqualTo(201);
        return json(result.body());
    }

    private HttpResult refresh(String refreshToken) {
        return call(HttpMethod.POST, "/api/v1/auth/refresh", null, "{\"refreshToken\":\"%s\"}".formatted(refreshToken));
    }

    private String rotate(String refreshToken) {
        HttpResult result = refresh(refreshToken);
        assertThat(result.status()).isEqualTo(200);
        return json(result.body()).get("refreshToken").asString();
    }
}
