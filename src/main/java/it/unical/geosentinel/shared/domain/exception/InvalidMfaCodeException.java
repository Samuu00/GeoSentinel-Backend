// Eccezione della verifica in due passaggi (TOTP): il codice è sbagliato, è già stato usato oppure il
// codice di recupero non è valido. Si usa un solo errore per tutti i casi, come per il reset della
// password, così un attaccante non capisce quale controllo è fallito.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Codice TOTP o di recupero non valido. Sulle rotte {@code /mfa}, con l'utente già autenticato, diventa
 * un 422 {@code invalid-mfa-code}; al login si usa la sottoclasse {@link InvalidLoginMfaCodeException}.
 */
public class InvalidMfaCodeException extends DomainException {
    /**
     * Crea l'eccezione con un messaggio fisso che non distingue il motivo del rifiuto.
     */
    public InvalidMfaCodeException() {
        super("Invalid two-factor code");
    }
}
