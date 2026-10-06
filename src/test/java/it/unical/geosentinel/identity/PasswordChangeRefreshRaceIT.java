package it.unical.geosentinel.identity;

import it.unical.geosentinel.identity.application.AuthService;
import it.unical.geosentinel.identity.application.AuthTokens;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Audit 2026-10, pista 1: una rotazione del refresh token non ancora committata non deve sopravvivere al cambio
// password. Senza il lock sull'utente, revokeAllOfUser (READ COMMITTED) non vedeva il successore appena inserito
class PasswordChangeRefreshRaceIT extends AbstractIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void refreshInFlightDuringPasswordChangeIsRevoked() throws Exception {
        User hiker = createUser(Role.HIKER);
        JsonNode session = json(call(HttpMethod.POST, "/api/v1/auth/login", null, """
                {"email":"%s","password":"%s"}""".formatted(hiker.getEmail(), TEST_PASSWORD)).body());

        CountDownLatch rotated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // La rotazione resta aperta (non committata) finché il test non la rilascia
        CompletableFuture<AuthTokens> rotation = CompletableFuture.supplyAsync(() ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    AuthTokens tokens = authService.refresh(session.get("refreshToken").asString());
                    rotated.countDown();
                    await(release);
                    return tokens;
                }));
        assertThat(rotated.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<HttpResult> change = CompletableFuture.supplyAsync(() ->
                call(HttpMethod.POST, "/api/v1/users/me/password", session.get("accessToken").asString(), """
                        {"currentPassword":"%s","newPassword":"%s"}""".formatted(TEST_PASSWORD, "Nuova-" + TEST_PASSWORD)));
        // Il cambio password aspetta il lock sull'utente tenuto dalla rotazione (lock_timeout 3 s)
        Thread.sleep(500);
        assertThat(change).isNotDone();

        release.countDown();
        AuthTokens rotatedTokens = rotation.get(10, TimeUnit.SECONDS);
        assertThat(change.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);

        HttpResult refresh = call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"%s\"}".formatted(rotatedTokens.refreshToken()));
        assertThat(refresh.status()).isEqualTo(401);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
