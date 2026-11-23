// Test di integrazione delle notifiche delle allerte SOS, con chiamate HTTP vere e PostGIS in Testcontainers.
// Nessun SOS deve restare senza notifica ai soccorritori: la notifica parte da un listener asincrono dopo il
// commit della transazione, e un job di recupero rimanda quelle andate perse (per esempio se l'applicazione si
// ferma tra il commit e l'invio). La colonna notified_at segna le allerte già notificate.
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

/**
 * Verifica la notifica asincrona di un'allerta appena creata e il recupero, una sola volta, di una
 * notifica persa.
 */
class SosNotificationRecoveryIT extends AbstractIntegrationTest {

    /** Job di recupero delle notifiche perse; nei test i job pianificati sono spenti e lo si chiama a mano. */
    @Autowired
    private SosNotificationRecovery recovery;

    /**
     * Un escursionista lancia un SOS. Entro 10 secondi il listener asincrono deve aver notificato l'allerta,
     * cioè deve aver valorizzato {@code notified_at}.
     */
    @Test
    void raisedAlertIsNotifiedAsynchronouslyAfterCommit() throws InterruptedException {
        HttpResult raised = call(HttpMethod.POST, "/api/v1/sos", obtainToken(Role.HIKER), """
                {"location":{"lat":39.3050,"lon":16.5020}}
                """);
        assertThat(raised.status()).isEqualTo(201);
        UUID alertId = UUID.fromString(json(raised.body()).get("id").asString());

        // Il listener gira su un thread separato (pool sos-notify): si controlla ogni 100 ms per al massimo 10 s.
        Timestamp notifiedAt = null;
        for (int i = 0; i < 100 && notifiedAt == null; i++) {
            Thread.sleep(100);
            notifiedAt = notifiedAt(alertId);
        }
        assertThat(notifiedAt).isNotNull();
    }

    /**
     * Un'allerta attiva creata due minuti fa e mai notificata viene inserita direttamente nel database. Il
     * primo giro del job di recupero deve notificarla; il secondo non deve notificarla di nuovo, quindi
     * {@code notified_at} deve restare quello del primo giro.
     */
    @Test
    void missedNotificationIsDeliveredOnceByTheRecoveryJob() {
        UUID hikerId = createUser(Role.HIKER).getId();
        // Simula un'allerta salvata ma mai notificata, come se l'applicazione si fosse fermata tra il commit
        // e l'invio della notifica.
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
        assertThat(notifiedAt(alertId)).isEqualTo(first);
    }

    /**
     * Legge l'istante di notifica di un'allerta.
     *
     * @param alertId l'id dell'allerta
     * @return il valore di {@code notified_at}, oppure {@code null} se non è ancora stata notificata
     */
    private Timestamp notifiedAt(UUID alertId) {
        return jdbcTemplate.queryForObject("SELECT notified_at FROM sos_alerts WHERE id = ?", Timestamp.class, alertId);
    }
}
