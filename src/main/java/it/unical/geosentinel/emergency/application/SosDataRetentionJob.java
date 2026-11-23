// Job pianificato che applica il periodo di conservazione dei dati delle allerte SOS (GDPR).
// Posizione e note mediche sono dati personali, e le note anche dati sanitari: dopo 90 giorni dalla chiusura
// dell'allerta la posizione viene arrotondata a circa 1 km (resta utile per le statistiche sulle zone più
// a rischio) e le note mediche vengono cancellate.
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

/**
 * Componente Spring che ogni notte anonimizza le allerte SOS chiuse da più di {@link #RETENTION}.
 * Il lavoro vero lo fa la query {@code anonymizeClosedBefore} di {@link SosAlertRepository}.
 */
@Component
public class SosDataRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(SosDataRetentionJob.class);
    /** Per quanto tempo dopo la chiusura si conservano posizione precisa e note mediche. */
    static final Duration RETENTION = Duration.ofDays(90);

    private final SosAlertRepository sosAlertRepository;
    private final Clock clock;

    /**
     * @param sosAlertRepository repository delle allerte
     * @param clock orologio iniettato, sostituibile nei test
     */
    public SosDataRetentionJob(SosAlertRepository sosAlertRepository, Clock clock) {
        this.sosAlertRepository = sosAlertRepository;
        this.clock = clock;
    }

    /**
     * Anonimizza le allerte chiuse prima di adesso meno {@link #RETENTION}. Parte ogni notte alle 04:00 UTC.
     * Se il backend gira su più istanze il job parte su ognuna, ma non è un problema: l'UPDATE salta le righe
     * già anonimizzate, quindi le esecuzioni successive non trovano nulla da fare.
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    @Transactional
    public void anonymizeClosedAlerts() {
        Instant now = clock.instant();
        int anonymized = sosAlertRepository.anonymizeClosedBefore(now.minus(RETENTION), now);
        log.info("Anonymized {} SOS alerts closed more than {} days ago", anonymized, RETENTION.toDays());
    }
}
