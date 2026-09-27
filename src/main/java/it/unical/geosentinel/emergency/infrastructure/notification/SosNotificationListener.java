package it.unical.geosentinel.emergency.infrastructure.notification;

import it.unical.geosentinel.emergency.domain.SosAlertRaisedEvent;
import it.unical.geosentinel.emergency.infrastructure.persistence.SosAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;

@Component
public class SosNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(SosNotificationListener.class);

    private final SosAlertRepository sosAlertRepository;
    private final Clock clock;

    public SosNotificationListener(SosAlertRepository sosAlertRepository, Clock clock) {
        this.sosAlertRepository = sosAlertRepository;
        this.clock = clock;
    }

    // executor di AsyncConfig: CallerRunsPolicy, un SOS non si scarta mai anche a coda piena
    @Async("sosNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSosAlertRaised(SosAlertRaisedEvent event) {
        deliver(event);
    }

    // chiamato anche da SosNotificationRecovery: la stessa allerta può essere notificata due volte
    // (es. crash dopo l'invio e prima di markNotified), quindi chi riceve la notifica deve tollerare i duplicati
    public void deliver(SosAlertRaisedEvent event) {
        // niente coordinate né note mediche nei log: dati personali (la posizione si legge dal DB, per id)
        log.warn("CRITICAL: SOS Alert raised [ID: {}, RaisedAt: {}]", event.alertId(), event.raisedAt());

        try{
            broadcastToRescueDispatchers(event);
            notifyEmergencyResponseTeams(event);
            sosAlertRepository.markNotified(event.alertId(), clock.instant());
            log.info("SOS dispatch notifications delivered successfully for alert ID: {}", event.alertId());
        } catch (Exception e) {
            // notified_at resta NULL: SosNotificationRecovery la rilancia
            log.error("Failed to propagate SOS alert notifications for alert ID: {}", event.alertId(), e);
        }
    }

    private void broadcastToRescueDispatchers(SosAlertRaisedEvent event) {
        // Mock integrazione: STOMP/WebSocket o Firebase Cloud Messaging per KMP
        log.debug("Dispatching live alert {} to rescue operations center dashboard", event.alertId());
    }

    private void notifyEmergencyResponseTeams(SosAlertRaisedEvent event) {
        // Integrazione SMS/Radio API di fallback
        log.debug("Dispatching SMS/Radio notification for alert {}", event.alertId());
    }
}
