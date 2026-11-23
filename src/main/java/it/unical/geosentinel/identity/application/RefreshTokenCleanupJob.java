/*
 * Job pianificato che cancella dal database i refresh token delle sessioni scadute, per evitare che la
 * tabella cresca senza limite.
 */
package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.infrastructure.persistence.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Pulizia notturna dei refresh token scaduti.
 */
@Component
public class RefreshTokenCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    public RefreshTokenCleanupJob(RefreshTokenRepository refreshTokenRepository, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    /**
     * Cancella i token delle sessioni scadute ogni notte alle 03:30 UTC. Con più istanze del backend il job
     * gira su ognuna, ma non serve un lock distribuito: la DELETE è idempotente.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @Transactional
    public void deleteExpiredSessions() {
        int deleted = refreshTokenRepository.deleteExpired(clock.instant());
        log.info("Deleted {} refresh tokens of expired sessions", deleted);
    }
}
