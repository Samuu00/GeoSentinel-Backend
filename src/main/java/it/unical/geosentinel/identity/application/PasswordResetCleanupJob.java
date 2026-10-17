package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.infrastructure.persistence.PasswordResetTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

@Component
public class PasswordResetCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetCleanupJob.class);
    // I codici durano 15min, dopo un giorno dalla scadenza vengono eliminati
    private static final Duration RETENTION = Duration.ofDays(1);

    private final PasswordResetTokenRepository resetTokens;
    private final Clock clock;

    public PasswordResetCleanupJob(PasswordResetTokenRepository resetTokens, Clock clock) {
        this.resetTokens = resetTokens;
        this.clock = clock;
    }

    @Scheduled(cron = "0 35 3 * * ?", zone = "UTC")
    @Transactional
    public void deleteExpiredCode(){
        int deleted = resetTokens.deleteExpiredBefore(clock.instant().minus(RETENTION));
        log.info("Deleted {} expired password reset codes", deleted);
    }
}
