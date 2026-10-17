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

// UTF-8 esplicito: con SimpleMailMessage le lettere accentate dell'italiano possono arrivare come "?"
@Component
@ConditionalOnProperty(name = "geosentinel.mail.enabled", havingValue = "true")
public class SmtpPasswordResetMailer implements PasswordResetMailer {
    private final JavaMailSender mailSender;
    private final String from;

    public SmtpPasswordResetMailer(JavaMailSender mailSender, @Value("${geosentinel.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

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
            // Nessun dato dell'utente nel messaggio dell'eccezione
            throw new IllegalStateException("Password reset mail could not be built", e);
        }
        mailSender.send(message);
    }

    private static String body(String code, Duration validFor) {
        return """
                Hai chiesto di reimpostare la password di GeoSentinel.

                Il tuo codice è: %s

                Vale %d minuti. Inseriscilo nell'app, insieme alla nuova password.

                Se non l'hai chiesto tu, ignora questa email: la password non cambia.
                """.formatted(PasswordResetCode.display(code), validFor.toMinutes());
    }
}
