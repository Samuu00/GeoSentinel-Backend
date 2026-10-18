package it.unical.geosentinel.identity.application.port;

// Avvisi al titolare dell'account sulla verifica in due passaggi
public interface MfaAlertMailer {
    // Qualcuno ha la sua password ma non il secondo fattore
    void sendWrongCodeWarning(String email);

    // Un amministratore ha disattivato la sua verifica in due passaggi
    void sendAdminReset(String email);

    // L'utente (o chi ha il suo access token) ha attivato o disattivato la 2FA: se non è stato lui, lo sa subito
    void sendStatusChanged(String email, boolean enabled);
}