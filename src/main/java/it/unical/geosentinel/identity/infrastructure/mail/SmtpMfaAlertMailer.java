/*
 * Invio via SMTP delle email legate alla verifica in due passaggi (avvisi, azzeramento, cambio di stato,
 * codice di configurazione), attivo quando geosentinel.mail.enabled è vero.
 */
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
import java.time.Duration;

/**
 * Implementazione SMTP di {@link MfaAlertMailer}. Ha la stessa condizione del mailer del reset: con l'invio
 * delle email disattivato il bean non esiste e {@code MfaAlertMailListener} ignora gli eventi.
 */
@Component
@ConditionalOnProperty(name = "geosentinel.mail.enabled", havingValue = "true")
public class SmtpMfaAlertMailer implements MfaAlertMailer {
    /** Testo dell'avviso per codici di verifica sbagliati dopo una password giusta. */
    private static final String WRONG_CODE_BODY = """
            Qualcuno ha inserito la password del tuo account GeoSentinel, ma non il codice della verifica in due passaggi.

            Se sei stato tu, ignora questa email.

            Altrimenti cambia subito la password: si chiudono tutte le sessioni aperte.
            """;

    /** Testo dell'avviso di azzeramento da parte di un amministratore. */
    private static final String ADMIN_RESET_BODY = """
            La verifica in due passaggi del tuo account GeoSentinel è stata disattivata da un amministratore, su tua richiesta verificata.

            Tutte le sessioni aperte sono state chiuse: accedi di nuovo con la password.

            Se non hai chiesto tu la disattivazione, cambia subito la password e contatta il supporto.
            """;

    /** Testo dell'avviso di attivazione. */
    private static final String ENABLED_BODY = """
            La verifica in due passaggi del tuo account GeoSentinel è stata attivata.

            Se non sei stato tu, qualcuno ha accesso al tuo account: cambia subito la password e contatta il supporto.
            """;

    /** Testo dell'avviso di disattivazione. */
    private static final String DISABLED_BODY = """
            La verifica in due passaggi del tuo account GeoSentinel è stata disattivata.

            Se non sei stato tu, cambia subito la password e riattivala.
            """;

    private final JavaMailSender mailSender;
    private final String from;

    /**
     * @param mailSender client SMTP configurato da Spring Boot
     * @param from indirizzo del mittente
     */
    public SmtpMfaAlertMailer(JavaMailSender mailSender, @Value("${geosentinel.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /** {@inheritDoc} */
    @Override
    public void sendWrongCodeWarning(String email) {
        send(email, "Tentativo di accesso al tuo account GeoSentinel", WRONG_CODE_BODY);
    }

    /** {@inheritDoc} */
    @Override
    public void sendAdminReset(String email) {
        send(email, "Verifica in due passaggi disattivata", ADMIN_RESET_BODY);
    }

    /** {@inheritDoc} */
    @Override
    public void sendStatusChanged(String email, boolean enabled) {
        if (enabled) {
            send(email, "Verifica in due passaggi attivata", ENABLED_BODY);
        } else {
            send(email, "Verifica in due passaggi disattivata", DISABLED_BODY);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void sendSetupCode(String email, String code, Duration validFor) {
        send(email, "Codice per attivare la verifica in due passaggi", """
                Per attivare la verifica in due passaggi sul tuo account GeoSentinel inserisci nell'app questo codice:

                %s

                Vale %d minuti. Se non stai attivando la verifica in due passaggi, qualcuno ha accesso al tuo account: \
                cambia subito la password.
                """.formatted(code, validFor.toMinutes()));
    }

    /**
     * Costruisce e spedisce un'email di solo testo in UTF-8.
     *
     * @param email destinatario
     * @param subject oggetto
     * @param body testo
     * @throws IllegalStateException se il messaggio non può essere costruito
     */
    private void send(String email, String subject, String body) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(body, false);
        } catch (MessagingException e) {
            // Il messaggio dell'eccezione non contiene dati dell'utente
            throw new IllegalStateException("MFA mail could not be built", e);
        }
        mailSender.send(message);
    }
}