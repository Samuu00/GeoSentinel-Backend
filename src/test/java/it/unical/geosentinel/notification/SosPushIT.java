package it.unical.geosentinel.notification;

import it.unical.geosentinel.emergency.infrastructure.notification.SosNotificationRecovery;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.notification.application.port.PushNotifier;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

// Push FCM con un PushNotifier finto: destinatari per ruolo, payload senza dati personali,
// recupero dopo un invio fallito, pulizia dei token rifiutati e dei dispositivi di un account cancellato
@Import(SosPushIT.FakePushConfig.class)
class SosPushIT extends AbstractIntegrationTest {

    @Autowired
    private FakePushNotifier push;

    @Autowired
    private SosNotificationRecovery recovery;

    @BeforeEach
    void resetPush() {
        push.reset();
    }

    @Test
    void deviceTokenIsRegisteredMovedBetweenUsersAndUnregisteredOnlyByItsOwner() {
        String first = obtainToken(Role.HIKER);
        String second = obtainToken(Role.HIKER);

        assertThat(registerDevice(first, "shared-phone-token", "ANDROID").status()).isEqualTo(204);
        // stesso telefono, altro account: il token passa al secondo utente (upsert, niente 409)
        assertThat(registerDevice(second, "shared-phone-token", "IOS").status()).isEqualTo(204);
        assertThat(deviceCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT platform FROM device_tokens", String.class)).isEqualTo("IOS");

        // il primo utente non può più toglierlo (204 idempotente, nessun effetto)
        assertThat(call(HttpMethod.DELETE, "/api/v1/devices/shared-phone-token", first, null).status()).isEqualTo(204);
        assertThat(deviceCount()).isEqualTo(1);
        assertThat(call(HttpMethod.DELETE, "/api/v1/devices/shared-phone-token", second, null).status()).isEqualTo(204);
        assertThat(deviceCount()).isZero();
    }

    @Test
    void invalidDeviceRegistrationIsRejected() {
        String token = obtainToken(Role.HIKER);
        assertThat(registerDevice(token, "some-token", "WINDOWS_PHONE").status()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/api/v1/devices", token, "{\"platform\":\"ANDROID\"}").status()).isEqualTo(400);
        assertThat(registerDevice(token, "x".repeat(1025), "ANDROID").status()).isEqualTo(400);
        assertThat(registerDevice(null, "some-token", "ANDROID").status()).isEqualTo(401);
    }

    @Test
    void raisedSosIsPushedOnlyToActiveOperatorsWithoutPersonalData() throws InterruptedException {
        registerDevice(tokenFor(createUser(Role.RESCUE_OPERATOR)), "operator-phone", "ANDROID");
        User disabled = createUser(Role.RESCUE_OPERATOR);
        registerDevice(tokenFor(disabled), "disabled-operator-phone", "IOS");
        jdbcTemplate.update("UPDATE users SET enabled = false WHERE id = ?", disabled.getId());
        registerDevice(tokenFor(createUser(Role.HIKER)), "other-hiker-phone", "ANDROID");

        HttpResult raised = call(HttpMethod.POST, "/api/v1/sos", obtainToken(Role.HIKER), """
                {"location":{"lat":39.3050,"lon":16.5020},"medicalNotes":"diabetic"}
                """);
        assertThat(raised.status()).isEqualTo(201);
        String alertId = json(raised.body()).get("id").asString();

        awaitTrue(() -> notifiedAt(UUID.fromString(alertId)) != null);
        assertThat(push.sent).hasSize(1);
        FakePushNotifier.Sent sent = push.sent.getFirst();
        assertThat(sent.tokens()).containsExactly("operator-phone");
        assertThat(sent.message().data()).isEqualTo(Map.of("type", "SOS_RAISED", "alertId", alertId));
        assertThat(sent.message().title() + sent.message().body()).doesNotContain("diabetic", "39.3", "16.5");
    }

    @Test
    void failedPushLeavesAlertUnnotifiedAndRecoveryDeliversItLater() {
        registerDevice(tokenFor(createUser(Role.RESCUE_OPERATOR)), "operator-phone", "ANDROID");
        UUID alertId = insertUnnotifiedAlert();

        push.failing = true;
        recovery.redeliverMissed();
        assertThat(notifiedAt(alertId)).isNull();

        push.failing = false;
        recovery.redeliverMissed();
        assertThat(notifiedAt(alertId)).isNotNull();
        assertThat(push.sent).hasSize(1);
    }

    @Test
    void tokensRejectedByFcmAreDeleted() {
        registerDevice(tokenFor(createUser(Role.RESCUE_OPERATOR)), "operator-phone", "ANDROID");
        registerDevice(tokenFor(createUser(Role.RESCUE_OPERATOR)), "uninstalled-app", "IOS");
        push.invalidTokens.add("uninstalled-app");
        UUID alertId = insertUnnotifiedAlert();

        recovery.redeliverMissed();

        assertThat(notifiedAt(alertId)).isNotNull();
        assertThat(jdbcTemplate.queryForList("SELECT token FROM device_tokens", String.class))
                .containsExactly("operator-phone");
    }

    @Test
    void alertIsRetriedWhenNoDeviceReceivedItButNotForeverWhenNoDeviceIsLeft() {
        registerDevice(tokenFor(createUser(Role.RESCUE_OPERATOR)), "uninstalled-app", "IOS");
        push.invalidTokens.add("uninstalled-app");
        UUID alertId = insertUnnotifiedAlert();

        recovery.redeliverMissed();
        // nessun dispositivo raggiunto: si riprova; il token rifiutato è già cancellato
        assertThat(notifiedAt(alertId)).isNull();
        assertThat(deviceCount()).isZero();

        // al giro dopo non c'è più nessun dispositivo: l'allerta resta nella lista degli operatori, niente loop
        recovery.redeliverMissed();
        assertThat(notifiedAt(alertId)).isNotNull();
    }

    @Test
    void hikerIsPushedWhenTeamIsDispatchedAndWhenAlertIsResolved() throws InterruptedException {
        String hiker = obtainToken(Role.HIKER);
        registerDevice(hiker, "hiker-phone", "ANDROID");
        String operator = obtainToken(Role.RESCUE_OPERATOR);
        String alertId = json(call(HttpMethod.POST, "/api/v1/sos", hiker, """
                {"location":{"lat":39.3050,"lon":16.5020}}
                """).body()).get("id").asString();
        UUID teamId = insertTeam("Alpha");

        assertThat(call(HttpMethod.PATCH, "/api/v1/sos/" + alertId + "/dispatch", operator,
                "{\"teamId\":\"%s\"}".formatted(teamId)).status()).isEqualTo(200);
        awaitTrue(() -> pushedTypes("hiker-phone").contains("SOS_DISPATCHED"));

        assertThat(call(HttpMethod.PATCH, "/api/v1/sos/" + alertId + "/resolve", operator, null).status()).isEqualTo(200);
        awaitTrue(() -> pushedTypes("hiker-phone").contains("SOS_RESOLVED"));

        assertThat(push.sent).filteredOn(s -> s.tokens().contains("hiker-phone"))
                .allSatisfy(s -> assertThat(s.message().data()).containsEntry("alertId", alertId).hasSize(2));
    }

    @Test
    void deletedAccountLosesItsDevices() {
        String hiker = obtainToken(Role.HIKER);
        registerDevice(hiker, "hiker-phone", "ANDROID");

        assertThat(call(HttpMethod.DELETE, "/api/v1/users/me", hiker, null).status()).isEqualTo(204);
        assertThat(deviceCount()).isZero();
    }

    private HttpResult registerDevice(String accessToken, String deviceToken, String platform) {
        return call(HttpMethod.POST, "/api/v1/devices", accessToken,
                "{\"token\":\"%s\",\"platform\":\"%s\"}".formatted(deviceToken, platform));
    }

    // allerta di 2 minuti fa mai notificata: la trova SosNotificationRecovery
    private UUID insertUnnotifiedAlert() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO sos_alerts (id, hiker_id, location, location_updated_at, status, created_at)
                VALUES (gen_random_uuid(), ?, ST_SetSRID(ST_MakePoint(16.50, 39.30), 4326), now(), 'ACTIVE',
                        now() - interval '2 minutes')
                RETURNING id
                """, UUID.class, createUser(Role.HIKER).getId());
    }

    private Timestamp notifiedAt(UUID alertId) {
        return jdbcTemplate.queryForObject("SELECT notified_at FROM sos_alerts WHERE id = ?", Timestamp.class, alertId);
    }

    private int deviceCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM device_tokens", Integer.class);
    }

    private List<String> pushedTypes(String deviceToken) {
        return push.sent.stream()
                .filter(s -> s.tokens().contains(deviceToken))
                .map(s -> s.message().data().get("type"))
                .toList();
    }

    // i listener girano su sos-notify-*: si aspetta al più 10 s
    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @TestConfiguration
    static class FakePushConfig {
        @Bean
        @Primary
        FakePushNotifier fakePushNotifier() {
            return new FakePushNotifier();
        }
    }

    static class FakePushNotifier implements PushNotifier {
        record Sent(List<String> tokens, PushMessage message) {}

        final List<Sent> sent = new CopyOnWriteArrayList<>();
        final Set<String> invalidTokens = ConcurrentHashMap.newKeySet();
        volatile boolean failing;

        void reset() {
            sent.clear();
            invalidTokens.clear();
            failing = false;
        }

        @Override
        public Result send(Collection<String> tokens, PushMessage message) {
            if (failing) {
                throw new IllegalStateException("FCM unavailable (test)");
            }
            sent.add(new Sent(List.copyOf(tokens), message));
            Set<String> rejected = new HashSet<>(tokens);
            rejected.retainAll(invalidTokens);
            return new Result(tokens.size() - rejected.size(), rejected);
        }
    }
}
