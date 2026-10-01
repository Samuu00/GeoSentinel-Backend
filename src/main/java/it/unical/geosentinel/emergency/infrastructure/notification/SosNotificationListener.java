package it.unical.geosentinel.emergency.infrastructure.notification;

import it.unical.geosentinel.emergency.domain.SosAlertRaisedEvent;
import it.unical.geosentinel.emergency.domain.SosAlertStatusChangedEvent;
import it.unical.geosentinel.emergency.domain.SosStatus;
import it.unical.geosentinel.emergency.infrastructure.persistence.SosAlertRepository;
import it.unical.geosentinel.identity.application.UserQueryService;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.notification.application.DeviceService;
import it.unical.geosentinel.notification.application.DeviceService.DeliveryReport;
import it.unical.geosentinel.notification.application.port.PushNotifier.PushMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Push FCM. Nel payload solo tipo evento e id dell'allerta: posizione e note mediche l'app le legge dall'API
// con il proprio token, così non passano da Google/Apple né compaiono sulla schermata di blocco
@Component
public class SosNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(SosNotificationListener.class);

    private final SosAlertRepository sosAlertRepository;
    private final UserQueryService userQueryService;
    private final DeviceService deviceService;
    private final Clock clock;

    public SosNotificationListener(SosAlertRepository sosAlertRepository, UserQueryService userQueryService,
                                   DeviceService deviceService, Clock clock) {
        this.sosAlertRepository = sosAlertRepository;
        this.userQueryService = userQueryService;
        this.deviceService = deviceService;
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

        try {
            List<UUID> operators = userQueryService.activeUserIdsWithRole(Role.RESCUE_OPERATOR);
            DeliveryReport report = deviceService.sendToUsers(operators, new PushMessage(
                    "SOS alert", "A hiker needs help: open GeoSentinel", data("SOS_RAISED", event.alertId())));
            // Riuscito se almeno un dispositivo l'ha ricevuto: riprovare finché tutti lo ricevono ripeterebbe la push
            // ogni 30 s agli altri operatori per un solo token guasto. Nessun dispositivo registrato: non c'è a chi
            // riprovare, l'allerta resta comunque nella lista degli operatori (findActiveSosAlerts)
            if (report.devices() > 0 && report.delivered() == 0) {
                throw new IllegalStateException("SOS push not delivered to any of " + report.devices() + " device(s)");
            }
            if (report.devices() == 0) {
                log.warn("No rescue operator device registered for SOS alert {}", event.alertId());
            }
            sosAlertRepository.markNotified(event.alertId(), clock.instant());
            log.info("SOS push delivered to {}/{} device(s) for alert ID: {}", report.delivered(), report.devices(), event.alertId());
        } catch (Exception e) {
            // notified_at resta NULL: SosNotificationRecovery la rilancia
            log.error("Failed to propagate SOS alert notifications for alert ID: {}", event.alertId(), e);
        }
    }

    // Squadra in arrivo / allerta chiusa, all'escursionista. Best effort: niente recupero, lo stato l'app lo legge
    // comunque con GET /sos/active
    @Async("sosNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSosAlertStatusChanged(SosAlertStatusChangedEvent event) {
        String body = event.status() == SosStatus.DISPATCHED
                ? "A rescue team is on its way"
                : "Your SOS alert has been closed";
        try {
            deviceService.sendToUsers(List.of(event.hikerId()),
                    new PushMessage("SOS update", body, data("SOS_" + event.status().name(), event.alertId())));
        } catch (Exception e) {
            log.error("Failed to notify SOS status {} for alert ID: {}", event.status(), event.alertId(), e);
        }
    }

    private static Map<String, String> data(String type, UUID alertId) {
        return Map.of("type", type, "alertId", alertId.toString());
    }
}
