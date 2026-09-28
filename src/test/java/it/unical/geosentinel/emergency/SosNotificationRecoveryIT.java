package it.unical.geosentinel.emergency;

import it.unical.geosentinel.emergency.infrastructure.notification.SosNotificationRecovery;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Nessun SOS senza notifica: listener asincrono dopo il commit + recupero delle notifiche perse (notified_at)
class SosNotificationRecoveryIT extends AbstractIntegrationTest {

    @Autowired
    private SosNotificationRecovery recovery;

    @Test
    void raisedAlertIsNotifiedAsynchronouslyAfterCommit() throws InterruptedException {
        HttpResult raised = call(HttpMethod.POST, "/api/v1/sos", obtainToken(Role.HIKER), """
                {"location":{"lat":39.3050,"lon":16.5020}}
                """);
        assertThat(raised.status()).isEqualTo(201);
        UUID alertId = UUID.fromString(json(raised.body()).get("id").asString());

        // il listener gira su sos-notify-*: si aspetta al più 10 s
        Timestamp notifiedAt = null;
        for (int i = 0; i < 100 && notifiedAt == null; i++) {
            Thread.sleep(100);
            notifiedAt = notifiedAt(alertId);
        }
        assertThat(notifiedAt).isNotNull();
    }

    @Test
    void missedNotificationIsDeliveredOnceByTheRecoveryJob() {
        UUID hikerId = createUser(Role.HIKER).getId();
        // allerta di 2 minuti fa mai notificata (app fermata tra il commit e la notifica)
        UUID alertId = jdbcTemplate.queryForObject("""
                INSERT INTO sos_alerts (id, hiker_id, location, location_updated_at, status, created_at)
                VALUES (gen_random_uuid(), ?, ST_SetSRID(ST_MakePoint(16.50, 39.30), 4326), now(), 'ACTIVE',
                        now() - interval '2 minutes')
                RETURNING id
                """, UUID.class, hikerId);

        recovery.redeliverMissed();
        Timestamp first = notifiedAt(alertId);
        recovery.redeliverMissed();

        assertThat(first).isNotNull();
        // il secondo giro non la trova più: notified_at resta quello della prima notifica
        assertThat(notifiedAt(alertId)).isEqualTo(first);
    }

    private Timestamp notifiedAt(UUID alertId) {
        return jdbcTemplate.queryForObject("SELECT notified_at FROM sos_alerts WHERE id = ?", Timestamp.class, alertId);
    }
}
