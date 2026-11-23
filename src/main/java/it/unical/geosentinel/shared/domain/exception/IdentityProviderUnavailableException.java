// Eccezione lanciata quando il provider di identità esterno (le chiavi pubbliche di Google usate per
// verificare l'ID token) non risponde. Sta in shared perché la traduce GlobalExceptionHandler, che
// risponde 503 identity-provider-unavailable con l'header Retry-After.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Segnala un'indisponibilità temporanea del provider di login esterno. Il client può ritentare.
 */
public class IdentityProviderUnavailableException extends DomainException {
    /**
     * @param message descrizione interna dell'errore (al client va un messaggio generico)
     * @param cause   errore di rete o di parsing originale
     */
    public IdentityProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
