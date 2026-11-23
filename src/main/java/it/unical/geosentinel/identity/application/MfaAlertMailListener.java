/*
 * Listener che spedisce le email legate alla verifica in due passaggi (avvisi, azzeramento, cambio di stato,
 * codice di configurazione). Le email partono solo dopo il commit della transazione e in background.
 */
package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.application.port.MfaAlertMailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Riceve gli eventi pubblicati da {@link MfaService} e li trasforma in email. Con
 * {@code AFTER_COMMIT} un'email non parte se la transazione viene annullata. Il mailer arriva da un
 * {@link ObjectProvider} perché, con l'invio delle email disattivato nella configurazione, il bean non esiste
 * e in quel caso gli eventi si ignorano.
 */
@Component
public class MfaAlertMailListener {
    private static final Logger logger = LoggerFactory.getLogger(MfaAlertMailListener.class);
    private final ObjectProvider<MfaAlertMailer> mailer;

    public MfaAlertMailListener(ObjectProvider<MfaAlertMailer> mailer) {
        this.mailer = mailer;
    }

    /**
     * Avvisa il titolare di codici sbagliati al login.
     *
     * @param event evento con l'email del titolare
     */
    @Async("passwordResetMailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMfaAlert(MfaAlertRequested event) {
        mailer.ifAvailable(sender -> send(() -> sender.sendWrongCodeWarning(event.email()), "MFA alert"));
    }

    /**
     * Informa l'utente che un amministratore ha azzerato la sua verifica in due passaggi.
     *
     * @param event evento con l'email dell'utente
     */
    @Async("passwordResetMailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAdminReset(MfaAdminResetRequested event) {
        mailer.ifAvailable(sender -> send(() -> sender.sendAdminReset(event.email()), "MFA admin reset"));
    }

    /**
     * Informa l'utente dell'attivazione o della disattivazione.
     *
     * @param event evento con l'email e il nuovo stato
     */
    @Async("passwordResetMailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(MfaStatusChanged event) {
        mailer.ifAvailable(sender -> send(() -> sender.sendStatusChanged(event.email(), event.enabled()), "MFA status"));
    }

    /**
     * Spedisce il codice di conferma della configurazione.
     *
     * @param event evento con email, codice e validità
     */
    @Async("passwordResetMailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSetupCode(MfaSetupCodeRequested event) {
        mailer.ifAvailable(sender -> send(() -> sender.sendSetupCode(event.email(), event.code(), event.validFor()), "MFA setup code"));
    }

    /**
     * Esegue un invio e, se fallisce, lo registra nel log senza propagare l'errore.
     *
     * @param action invio da eseguire
     * @param kind tipo di email, usato solo nel messaggio di log
     */
    private void send(Runnable action, String kind) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // Solo il tipo dell'errore: il messaggio potrebbe contenere l'email
            logger.warn("{} mail failed: {}", kind, e.getClass().getSimpleName());
        }
    }
}
