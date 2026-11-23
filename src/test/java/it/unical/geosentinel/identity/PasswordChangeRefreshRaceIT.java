// Test di integrazione di una condizione di gara tra il rinnovo della sessione e il cambio password, con
// PostGIS e Redis in Testcontainers. Un refresh token appena ruotato, in una transazione non ancora
// confermata, non deve sopravvivere al cambio password. Senza un lock sulla riga dell'utente, la revoca di
// tutti i token (in isolamento READ COMMITTED) non vedrebbe il nuovo token inserito dalla rotazione in corso.
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

/**
 * Verifica che il cambio password aspetti una rotazione del refresh token in corso e poi revochi anche il
 * token appena emesso da quella rotazione.
 */
class PasswordChangeRefreshRaceIT extends AbstractIntegrationTest {

    /** Servizio di autenticazione, chiamato direttamente per tenere aperta la transazione della rotazione. */
    @Autowired
    private AuthService authService;

    /** Gestore delle transazioni, usato per aprire a mano la transazione della rotazione. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Scenario: l'utente fa login; in un thread separato parte una rotazione del refresh token dentro una
     * transazione che il test tiene aperta. Nel frattempo arriva il cambio password, che deve restare in attesa
     * del lock sull'utente. Quando la rotazione viene confermata, il cambio password termina con 200 e il
     * refresh token appena ruotato deve risultare revocato: usarlo dà 401.
     */
    @Test
    void refreshInFlightDuringPasswordChangeIsRevoked() throws Exception {
        User hiker = createUser(Role.HIKER);
        JsonNode session = json(call(HttpMethod.POST, "/api/v1/auth/login", null, """
                {"email":"%s","password":"%s"}""".formatted(hiker.getEmail(), TEST_PASSWORD)).body());

        CountDownLatch rotated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // La transazione della rotazione resta aperta, senza commit, finché il test non apre il latch "release".
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
        // Il cambio password è bloccato sul lock dell'utente tenuto dalla rotazione. Il database lo farebbe
        // fallire dopo 3 secondi (lock_timeout), quindi mezzo secondo di attesa basta a vedere che è fermo.
        Thread.sleep(500);
        assertThat(change).isNotDone();

        release.countDown();
        AuthTokens rotatedTokens = rotation.get(10, TimeUnit.SECONDS);
        assertThat(change.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);

        HttpResult refresh = call(HttpMethod.POST, "/api/v1/auth/refresh", null,
                "{\"refreshToken\":\"%s\"}".formatted(rotatedTokens.refreshToken()));
        assertThat(refresh.status()).isEqualTo(401);
    }

    /**
     * Aspetta l'apertura di un latch per al massimo 10 secondi, trasformando l'interruzione in un'eccezione
     * non controllata (serve dentro la lambda della transazione, che non può lanciare eccezioni controllate).
     *
     * @param latch il latch da attendere
     * @throws IllegalStateException se il thread viene interrotto durante l'attesa
     */
    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
