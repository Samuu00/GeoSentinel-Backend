/*
 * Risultato del login con Google restituito da AuthService.loginWithGoogle.
 */
package it.unical.geosentinel.identity.application;

/**
 * Esito del login con Google.
 *
 * @param tokens token della sessione aperta
 * @param created {@code true} solo se l'account è stato appena creato: il controller risponde 201 invece di 200
 */
public record GoogleSignIn(AuthTokens tokens, boolean created) {}
