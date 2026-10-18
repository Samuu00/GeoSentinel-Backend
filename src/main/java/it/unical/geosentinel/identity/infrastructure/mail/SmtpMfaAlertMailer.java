package it.unical.geosentinel.identity.infrastructure.mail;

import it.unical.geosentinel.identity.application.port.MfaAlertMailer;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

// Stessa condizione del mailer di reset: con la mail spenta non esiste, e il listener non fallisce
@Component
@ConditionalOnProperty(name = "geosentinel.mail.enabled", havingValue = "true")
public class SmtpMfaAlertMailer implements MfaAlertMailer {
    private static final String WRONG_CODE_BODY = """
            Qualcuno ha inserito la password del tuo account GeoSentinel, ma non il codice della verifica in due passaggi.

            Se sei stato tu, ignora questa email.

            Altrimenti cambia subito la password: si chiudono tutte le sessioni aperte.
            """;

    private static final String ADMIN_RESET_BODY = """
            La verifica in due passaggi del tuo account GeoSentinel è stata disattivata da un amministratore, su tua richiesta verificata.

            Tutte le sessioni aperte sono state chiuse: accedi di nuovo con la password.

            Se non hai chiesto tu la disattivazione, cambia subito la password e contatta il supporto.
            """;

    private static final String ENABLED_BODY = """
            La verifica in due passaggi del tuo account GeoSentinel è stata attivata.

            Se non sei stato tu, qualcuno ha accesso al tuo account: cambia subito la password e contatta il supporto.
            """;

    private static final String DISABLED_BODY = """
            La verifica in due passaggi del tuo account GeoSentinel è stata disattivata.

            Se non sei stato tu, cambia subito la password e riattivala.
            """;

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpMfaAlertMailer(JavaMailSender mailSender, @Value("${geosentinel.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendWrongCodeWarning(String email) {
        send(email, "Tentativo di accesso al tuo account GeoSentinel", WRONG_CODE_BODY);
    }

    @Override
    public void sendAdminReset(String email) {
        send(email, "Verifica in due passaggi disattivata", ADMIN_RESET_BODY);
    }

    @Override
    public void sendStatusChanged(String email, boolean enabled) {
        if (enabled) {
            send(email, "Verifica in due passaggi attivata", ENABLED_BODY);
        } else {
            send(email, "Verifica in due passaggi disattivata", DISABLED_BODY);
        }
    }

    private void send(String email, String subject, String body) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(body, false);
        } catch (MessagingException e) {
            // Nessun dato dell'utente nel messaggio dell'eccezione
            throw new IllegalStateException("MFA mail could not be built", e);
        }
        mailSender.send(message);
    }
}