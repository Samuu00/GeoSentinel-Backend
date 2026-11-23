/*
 * Porta per l'invio del codice di reset della password. Ha due implementazioni: una via SMTP e una che si
 * limita al log, usata quando l'invio delle email è disattivato.
 */
package it.unical.geosentinel.identity.application.port;

import java.time.Duration;

/**
 * Invio del codice di reset della password.
 */
public interface PasswordResetMailer {
    /**
     * @param email destinatario
     * @param code codice in chiaro, nella forma normalizzata
     * @param validFor validità del codice, da indicare nel testo
     */
    void sendCode(String email, String code, Duration validFor);
}
