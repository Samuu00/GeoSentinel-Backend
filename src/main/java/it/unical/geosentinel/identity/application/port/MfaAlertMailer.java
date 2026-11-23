/*
 * Porta per le email inviate al titolare dell'account a proposito della verifica in due passaggi.
 * L'implementazione SMTP sta in infrastructure/mail; se l'invio delle email è disattivato, il bean non esiste.
 */
package it.unical.geosentinel.identity.application.port;

import java.time.Duration;

/**
 * Invio delle email legate alla verifica in due passaggi.
 */
public interface MfaAlertMailer {
    /**
     * Avvisa che qualcuno ha inserito la password giusta ma codici di verifica sbagliati.
     *
     * @param email destinatario
     */
    void sendWrongCodeWarning(String email);

    /**
     * Informa che un amministratore ha disattivato la verifica in due passaggi dell'utente.
     *
     * @param email destinatario
     */
    void sendAdminReset(String email);

    /**
     * Informa che la verifica in due passaggi è stata attivata o disattivata. Se non è stato il titolare
     * (ma qualcuno con il suo access token), lo viene a sapere subito.
     *
     * @param email destinatario
     * @param enabled {@code true} se è stata attivata
     */
    void sendStatusChanged(String email, boolean enabled);

    /**
     * Spedisce il codice che conferma la configurazione: prova che chi la attiva possiede l'email dell'account.
     *
     * @param email destinatario
     * @param code codice formattato per la lettura
     * @param validFor validità del codice
     */
    void sendSetupCode(String email, String code, Duration validFor);
}
