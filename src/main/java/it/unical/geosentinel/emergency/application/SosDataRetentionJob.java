package it.unical.geosentinel.emergency.application;

import it.unical.geosentinel.emergency.infrastructure.persistence.SosAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

// GDPR: posizione e note mediche sono dati personali (le note anche sanitari). 90 giorni dopo la chiusura
// la posizione si arrotonda a ~1 km (resta utile per le statistiche sulle zone) e le note si cancellano
@Component
public class SosDataRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(SosDataRetentionJob.class);
    static final Duration RETENTION = Duration.ofDays(90);

    private final SosAlertRepository sosAlertRepository;
    private final Clock clock;

    public SosDataRetentionJob(SosAlertRepository sosAlertRepository, Clock clock) {
        this.sosAlertRepository = sosAlertRepository;
        this.clock = clock;
    }

    // Ogni notte alle 04:00 UTC. Con più istanze gira su ognuna: l'UPDATE salta le righe già anonimizzate
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    @Transactional
    public void anonymizeClosedAlerts() {
        Instant now = clock.instant();
        int anonymized = sosAlertRepository.anonymizeClosedBefore(now.minus(RETENTION), now);
        log.info("Anonymized {} SOS alerts closed more than {} days ago", anonymized, RETENTION.toDays());
    }
}
