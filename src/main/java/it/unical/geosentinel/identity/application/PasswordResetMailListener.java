package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.application.port.PasswordResetMailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

// Emissione e invio del codice fuori dal thread della richiesta (vedi PasswordResetService.requestReset).
// @EventListener e non @TransactionalEventListener: requestReset non apre transazioni, la transazione è in issueCode.
// Executor dedicato: un SMTP lento non tocca quello degli SOS
@Component
public class PasswordResetMailListener {
    private static final Logger logger = LoggerFactory.getLogger(PasswordResetMailListener.class);
    private final PasswordResetService passwordResetService;
    private final PasswordResetMailer mailer;

    public PasswordResetMailListener(PasswordResetService passwordResetService, PasswordResetMailer mailer) {
        this.passwordResetService = passwordResetService;
        this.mailer = mailer;
    }

    @Async("passwordResetMailExecutor")
    @EventListener
    public void onPasswordResetRequested(PasswordResetRequested event) {
        try {
            // issueCode ha già fatto commit quando restituisce il codice
            passwordResetService.issueCode(event.normalizedEmail())
                    .ifPresent(issued -> mailer.sendCode(issued.email(), issued.code(), issued.validFor()));
        } catch (RuntimeException e) {
            // Solo il tipo dell'errore: il messaggio può contenere l'email
            logger.warn("Password reset mail failed: {}", e.getClass().getSimpleName());
        }
    }
}
