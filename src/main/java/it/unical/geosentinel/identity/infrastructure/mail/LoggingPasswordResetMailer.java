package it.unical.geosentinel.identity.infrastructure.mail;

import it.unical.geosentinel.identity.application.port.PasswordResetMailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Profilo di default (test, CI, senza SMTP): non manda nulla. Il log non contiene né email né codice
@Component
@ConditionalOnProperty(name = "geosentinel.mail.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingPasswordResetMailer implements PasswordResetMailer {
    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetMailer.class);

    @Override
    public void sendCode(String email, String code, Duration validFor) {
        log.info("Password reset mail not sent: mail disabled");
    }
}
