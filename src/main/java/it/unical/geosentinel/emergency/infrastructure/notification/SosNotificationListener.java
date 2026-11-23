// Listener che trasforma gli eventi delle allerte SOS in notifiche push.
// Alla nascita di un'allerta avvisa tutti gli operatori del soccorso; quando una squadra viene inviata o
// l'allerta viene risolta avvisa l'escursionista. Gira dopo il commit e su un executor dedicato, così l'invio
// delle push non rallenta né fa fallire la richiesta HTTP che ha creato o modificato l'allerta.
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

/**
 * Invio delle push legate alle allerte SOS tramite {@link DeviceService}.
 * <p>
 * Il payload contiene solo il tipo di evento e l'id dell'allerta. Posizione e note mediche l'app le legge
 * dall'API con il proprio token: in questo modo non passano dai server di Google e Apple e non compaiono
 * sulla schermata di blocco del telefono.
 */
@Component
public class SosNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(SosNotificationListener.class);

    private final SosAlertRepository sosAlertRepository;
    private final UserQueryService userQueryService;
    private final DeviceService deviceService;
    private final Clock clock;

    /**
     * @param sosAlertRepository repository delle allerte, per segnare quelle notificate
     * @param userQueryService servizio da cui si ricavano gli operatori attivi
     * @param deviceService servizio che invia le push ai dispositivi registrati
     * @param clock orologio iniettato, sostituibile nei test
     */
    public SosNotificationListener(SosAlertRepository sosAlertRepository, UserQueryService userQueryService,
                                   DeviceService deviceService, Clock clock) {
        this.sosAlertRepository = sosAlertRepository;
        this.userQueryService = userQueryService;
        this.deviceService = deviceService;
        this.clock = clock;
    }

    /**
     * Riceve l'evento di una nuova allerta dopo il commit e avvia la notifica agli operatori. L'executor
     * {@code sosNotificationExecutor} (definito in AsyncConfig) usa la CallerRunsPolicy: se la coda è piena il
     * lavoro viene eseguito dal thread chiamante, quindi la notifica di un SOS non viene mai scartata.
     *
     * @param event dati della nuova allerta
     */
    @Async("sosNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSosAlertRaised(SosAlertRaisedEvent event) {
        deliver(event);
    }

    /**
     * Invia la push "Nuovo SOS" a tutti gli operatori del soccorso attivi e, se la consegna è riuscita, segna
     * l'allerta come notificata. Gli errori non vengono propagati: notified_at resta vuoto e sarà
     * {@link SosNotificationRecovery} a riprovare.
     * <p>
     * Il metodo è chiamato anche dal recupero, quindi la stessa allerta può essere notificata due volte (per
     * esempio se il processo si ferma dopo l'invio ma prima di markNotified): l'app che riceve la notifica deve
     * tollerare i duplicati.
     *
     * @param event dati dell'allerta da notificare
     */
    public void deliver(SosAlertRaisedEvent event) {
        // Nel log solo id e istante: coordinate e note mediche sono dati personali e restano nel database.
        log.warn("CRITICAL: SOS Alert raised [ID: {}, RaisedAt: {}]", event.alertId(), event.raisedAt());

        try {
            List<UUID> operators = userQueryService.activeUserIdsWithRole(Role.RESCUE_OPERATOR);
            DeliveryReport report = deviceService.sendToUsers(operators, new PushMessage(
                    "Nuovo SOS", "Un escursionista ha chiesto aiuto. Tocca per aprire le allerte.", data("SOS_RAISED", event.alertId())));
            // L'invio si considera riuscito se almeno un dispositivo ha ricevuto la push. Pretendere che la
            // ricevano tutti farebbe ripetere la push ogni 30 secondi agli altri operatori a causa di un solo
            // token guasto. Se non c'è nessun dispositivo registrato non c'è nessuno a cui riprovare: l'allerta
            // resta comunque nella lista che gli operatori consultano dall'app (findActiveSosAlerts).
            if (report.devices() > 0 && report.delivered() == 0) {
                throw new IllegalStateException("SOS push not delivered to any of " + report.devices() + " device(s)");
            }
            if (report.devices() == 0) {
                log.warn("No rescue operator device registered for SOS alert {}", event.alertId());
            }
            sosAlertRepository.markNotified(event.alertId(), clock.instant());
            log.info("SOS push delivered to {}/{} device(s) for alert ID: {}", report.delivered(), report.devices(), event.alertId());
        } catch (Exception e) {
            // notified_at resta NULL, quindi SosNotificationRecovery riproverà al prossimo giro.
            log.error("Failed to propagate SOS alert notifications for alert ID: {}", event.alertId(), e);
        }
    }

    /**
     * Avvisa l'escursionista che una squadra è in arrivo o che l'allerta è stata chiusa. L'invio è "best
     * effort": se fallisce non c'è un meccanismo di recupero, perché l'app legge comunque lo stato aggiornato
     * interrogando periodicamente GET /sos/active.
     *
     * @param event allerta, escursionista e nuovo stato
     */
    @Async("sosNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSosAlertStatusChanged(SosAlertStatusChangedEvent event) {
        // I testi sono in italiano e coincidono con quelli che l'app mostra quando la push arriva con l'app aperta.
        boolean dispatched = event.status() == SosStatus.DISPATCHED;
        String title = dispatched ? "Soccorso in arrivo" : "Allerta chiusa";
        String body = dispatched
                ? "Una squadra è stata inviata verso di te. Tieni l’app aperta."
                : "Il soccorso ha chiuso la tua richiesta di aiuto.";
        try {
            deviceService.sendToUsers(List.of(event.hikerId()),
                    new PushMessage(title, body, data("SOS_" + event.status().name(), event.alertId())));
        } catch (Exception e) {
            log.error("Failed to notify SOS status {} for alert ID: {}", event.status(), event.alertId(), e);
        }
    }

    /**
     * Costruisce il payload dati della push: solo il tipo di evento e l'id dell'allerta.
     *
     * @param type tipo di evento letto dall'app (per esempio SOS_RAISED)
     * @param alertId id dell'allerta da aprire
     * @return la mappa dei dati
     */
    private static Map<String, String> data(String type, UUID alertId) {
        return Map.of("type", type, "alertId", alertId.toString());
    }
}
