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
    void reusingARefreshTokenRevokesTheWholeFamily() {
        String first = register().get("refreshToken").asString();
        HttpResult rotated = refresh(first);
        assertThat(rotated.status()).isEqualTo(200);
        String second = json(rotated.body()).get("refreshToken").asString();

        // riuso del token già ruotato: 401, e anche il token nuovo della stessa famiglia è revocato
        assertThat(refresh(first).status()).isEqualTo(401);
        assertThat(refresh(second).status()).isEqualTo(401);
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
}
