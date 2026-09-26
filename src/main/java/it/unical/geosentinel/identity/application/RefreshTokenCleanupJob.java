package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.infrastructure.persistence.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
public class RefreshTokenCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    public RefreshTokenCleanupJob(RefreshTokenRepository refreshTokenRepository, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    // Ogni notte alle 03:30 UTC. Con più istanze gira su ognuna: il DELETE è idempotente, nessun lock distribuito
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @Transactional
    public void deleteExpiredSessions() {
        int deleted = refreshTokenRepository.deleteExpired(clock.instant());
        log.info("Deleted {} refresh tokens of expired sessions", deleted);
    }
}
