/*
 * Invio via SMTP dell'email con il codice di reset della password, attivo quando geosentinel.mail.enabled
 * è vero.
 */
package it.unical.geosentinel.identity.infrastructure.mail;

import it.unical.geosentinel.identity.application.port.PasswordResetMailer;
import it.unical.geosentinel.identity.domain.PasswordResetCode;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Implementazione SMTP di {@link PasswordResetMailer}. Si usa un {@link MimeMessage} con codifica UTF-8
 * esplicita perché con {@code SimpleMailMessage} le lettere accentate dell'italiano potrebbero arrivare come "?".
 */
@Component
@ConditionalOnProperty(name = "geosentinel.mail.enabled", havingValue = "true")
public class SmtpPasswordResetMailer implements PasswordResetMailer {
    private final JavaMailSender mailSender;
    private final String from;

    /**
     * @param mailSender client SMTP configurato da Spring Boot
     * @param from indirizzo del mittente
     */
    public SmtpPasswordResetMailer(JavaMailSender mailSender, @Value("${geosentinel.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException se il messaggio non può essere costruito
     */
    @Override
    public void sendCode(String email, String code, Duration validFor){
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Il tuo codice GeoSentinel");
            helper.setText(body(code, validFor), false);
        } catch (MessagingException e) {
            // Il messaggio dell'eccezione non contiene dati dell'utente
            throw new IllegalStateException("Password reset mail could not be built", e);
        }
        mailSender.send(message);
    }

    /**
     * @param code codice normalizzato
     * @param validFor validità del codice
     * @return il testo dell'email, con il codice nella forma a due gruppi
     */
    private static String body(String code, Duration validFor) {
        return """
                Hai chiesto di reimpostare la password di GeoSentinel.

                Il tuo codice è: %s

                Vale %d minuti. Inseriscilo nell'app, insieme alla nuova password.

                Se non l'hai chiesto tu, ignora questa email: la password non cambia.
                """.formatted(PasswordResetCode.display(code), validFor.toMinutes());
    }
}
