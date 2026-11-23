// Job di recupero delle notifiche SOS non consegnate.
// L'evento che fa partire le push vive solo in memoria: se il backend si ferma tra il commit dell'allerta e
// l'invio della notifica, l'SOS resterebbe senza notifica. Questo job ritrova quelle allerte e le rinotifica.
package it.unical.geosentinel.emergency.infrastructure.notification;

import it.unical.geosentinel.emergency.domain.SosAlert;
import it.unical.geosentinel.emergency.domain.SosAlertRaisedEvent;
import it.unical.geosentinel.emergency.infrastructure.persistence.SosAlertRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Versione minima del pattern transactional outbox: la colonna notified_at di sos_alerts fa da coda delle
 * notifiche ancora da consegnare. Ogni 30 secondi il job cerca le allerte aperte senza notified_at e le passa
 * di nuovo a {@link SosNotificationListener#deliver}. Se il backend gira su più istanze il job parte su
 * ognuna e qualche push può arrivare doppia, cosa che l'app tollera.
 */
@Component
public class SosNotificationRecovery {
    /**
     * Età minima di un'allerta prima di considerarla persa: sotto questo margine la notifica asincrona è
     * probabilmente ancora in corso e va lasciata finire.
     */
    private static final Duration GRACE = Duration.ofSeconds(60);

    private final SosAlertRepository sosAlertRepository;
    private final SosNotificationListener listener;
    private final Clock clock;

    /**
     * @param sosAlertRepository repository delle allerte
     * @param listener listener che esegue l'invio vero e proprio
     * @param clock orologio iniettato, sostituibile nei test
     */
    public SosNotificationRecovery(SosAlertRepository sosAlertRepository, SosNotificationListener listener, Clock clock) {
        this.sosAlertRepository = sosAlertRepository;
        this.listener = listener;
        this.clock = clock;
    }

    /**
     * Rinotifica le allerte aperte create da più di {@link #GRACE} e mai segnate come notificate.
     * Parte 30 secondi dopo la fine dell'esecuzione precedente.
     */
    @Scheduled(fixedDelay = 30_000)
    public void redeliverMissed() {
        for (SosAlert alert : sosAlertRepository.findUnnotifiedCreatedBefore(clock.instant().minus(GRACE))) {
            listener.deliver(new SosAlertRaisedEvent(
                    alert.getId(), alert.getLocation().getX(), alert.getLocation().getY(), alert.getCreatedAt()));
        }
    }
}
