package it.unical.geosentinel.emergency.infrastructure.notification;

import it.unical.geosentinel.emergency.domain.SosAlert;
import it.unical.geosentinel.emergency.domain.SosAlertRaisedEvent;
import it.unical.geosentinel.emergency.infrastructure.persistence.SosAlertRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

// L'evento vive in memoria: se l'app si ferma tra il commit del raise e la notifica, l'SOS resta senza notifica.
// Versione minima del transactional outbox: notified_at fa da coda. Con più istanze gira su ognuna (duplicati tollerati)
@Component
public class SosNotificationRecovery {
    // margine per il listener asincrono: sotto questa età la notifica è probabilmente ancora in corso
    private static final Duration GRACE = Duration.ofSeconds(60);

    private final SosAlertRepository sosAlertRepository;
    private final SosNotificationListener listener;
    private final Clock clock;

    public SosNotificationRecovery(SosAlertRepository sosAlertRepository, SosNotificationListener listener, Clock clock) {
        this.sosAlertRepository = sosAlertRepository;
        this.listener = listener;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 30_000)
    public void redeliverMissed() {
        for (SosAlert alert : sosAlertRepository.findUnnotifiedCreatedBefore(clock.instant().minus(GRACE))) {
            listener.deliver(new SosAlertRaisedEvent(
                    alert.getId(), alert.getLocation().getX(), alert.getLocation().getY(), alert.getCreatedAt()));
        }
    }
}
