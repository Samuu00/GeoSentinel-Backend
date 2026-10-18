package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.application.port.MfaAlertMailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// Dopo il commit, fuori dal thread della richiesta. ObjectProvider: con la mail spenta il bean non c'è
@Component
public class MfaAlertMailListener {
    private static final Logger logger = LoggerFactory.getLogger(MfaAlertMailListener.class);
    private final ObjectProvider<MfaAlertMailer> mailer;

    public MfaAlertMailListener(ObjectProvider<MfaAlertMailer> mailer) {
        this.mailer = mailer;
    }

    @Async("passwordResetMailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMfaAlert(MfaAlertRequested event) {
        mailer.ifAvailable(sender -> send(() -> sender.sendWrongCodeWarning(event.email()), "MFA alert"));
    }

    @Async("passwordResetMailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAdminReset(MfaAdminResetRequested event) {
        mailer.ifAvailable(sender -> send(() -> sender.sendAdminReset(event.email()), "MFA admin reset"));
    }

    @Async("passwordResetMailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(MfaStatusChanged event) {
        mailer.ifAvailable(sender -> send(() -> sender.sendStatusChanged(event.email(), event.enabled()), "MFA status"));
    }

    private void send(Runnable action, String kind) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // Solo il tipo dell'errore: il messaggio può contenere l'email
            logger.warn("{} mail failed: {}", kind, e.getClass().getSimpleName());
        }
    }
}