/*
 * Listener che, ricevuta una richiesta di reset della password, emette il codice e lo spedisce via email in
 * background, fuori dal thread della richiesta HTTP.
 */
package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.application.port.PasswordResetMailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Gestisce l'evento {@link PasswordResetRequested}. Lavorare in background fa sì che la risposta HTTP arrivi
 * sempre nello stesso tempo, che l'email esista o no (vedi {@code PasswordResetService.requestReset}).
 * Si usa {@code @EventListener} e non {@code @TransactionalEventListener} perché {@code requestReset} non apre
 * transazioni: la transazione sta in {@code issueCode}. L'executor è dedicato, così un server SMTP lento non
 * occupa i thread usati per gli SOS.
 */
@Component
public class PasswordResetMailListener {
    private static final Logger logger = LoggerFactory.getLogger(PasswordResetMailListener.class);
    private final PasswordResetService passwordResetService;
    private final PasswordResetMailer mailer;

    public PasswordResetMailListener(PasswordResetService passwordResetService, PasswordResetMailer mailer) {
        this.passwordResetService = passwordResetService;
        this.mailer = mailer;
    }

    /**
     * Emette il codice e lo spedisce. Se l'email non corrisponde a un account attivo non succede nulla.
     * Gli errori vengono solo registrati nel log, perché il client ha già ricevuto la risposta.
     *
     * @param event richiesta di reset con l'email normalizzata
     */
    @Async("passwordResetMailExecutor")
    @EventListener
    public void onPasswordResetRequested(PasswordResetRequested event) {
        try {
            // Quando issueCode restituisce il codice, la sua transazione è già stata committata
            passwordResetService.issueCode(event.normalizedEmail())
                    .ifPresent(issued -> mailer.sendCode(issued.email(), issued.code(), issued.validFor()));
        } catch (RuntimeException e) {
            // Solo il tipo dell'errore: il messaggio potrebbe contenere l'email
            logger.warn("Password reset mail failed: {}", e.getClass().getSimpleName());
        }
    }
}
