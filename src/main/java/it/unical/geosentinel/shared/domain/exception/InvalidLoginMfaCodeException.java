// Variante di InvalidMfaCodeException usata durante il login. Lo slug è lo stesso (invalid-mfa-code), ma
// lo status è 401 perché in quel momento il client non è ancora autenticato. GlobalExceptionHandler ha un
// handler per entrambe le classi e Spring sceglie quello della sottoclasse più specifica.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Codice della verifica in due passaggi non valido inserito durante il login: tradotto in 401
 * {@code invalid-mfa-code}.
 */
public class InvalidLoginMfaCodeException extends InvalidMfaCodeException {}
