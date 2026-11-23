/*
 * Dati dell'utente estratti da un ID token di Google già verificato. È il valore restituito dalla porta
 * GoogleIdentityVerifier e consumato da AuthService.loginWithGoogle.
 */
package it.unical.geosentinel.identity.application.port;

/**
 * Identità Google verificata.
 *
 * @param subject identificativo stabile dell'utente presso Google (claim {@code sub})
 * @param email email dell'account Google
 * @param emailVerified se Google ha verificato l'email (claim {@code email_verified})
 * @param displayName nome dell'utente, può essere {@code null}
 */
public record GoogleIdentity(String subject, String email, boolean emailVerified, String displayName) {}
