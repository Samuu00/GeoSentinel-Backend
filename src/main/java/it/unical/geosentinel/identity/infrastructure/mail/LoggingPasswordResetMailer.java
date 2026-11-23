/*
 * Mailer del reset della password usato quando l'invio delle email è disattivato (test, CI, ambienti senza
 * SMTP): non spedisce nulla e lo scrive nel log.
 */
package it.unical.geosentinel.identity.infrastructure.mail;

import it.unical.geosentinel.identity.application.port.PasswordResetMailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Implementazione di {@link PasswordResetMailer} attiva quando {@code geosentinel.mail.enabled} è falso o
 * assente. Il messaggio di log non contiene né l'email né il codice.
 */
@Component
@ConditionalOnProperty(name = "geosentinel.mail.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingPasswordResetMailer implements PasswordResetMailer {
    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetMailer.class);

    /** Non invia nulla: registra solo che l'email non è partita. */
    @Override
    public void sendCode(String email, String code, Duration validFor) {
        log.info("Password reset mail not sent: mail disabled");
    }
}
