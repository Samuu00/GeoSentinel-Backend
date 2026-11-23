/*
 * Dati restituiti all'avvio della configurazione della verifica in due passaggi.
 */
package it.unical.geosentinel.identity.application;

/**
 * Informazioni per configurare l'app di autenticazione.
 *
 * @param secret segreto TOTP in Base32, per l'inserimento a mano
 * @param otpauthUri URI {@code otpauth://} che il client trasforma in QR code
 */
public record MfaSetupView(String secret, String otpauthUri) {}
