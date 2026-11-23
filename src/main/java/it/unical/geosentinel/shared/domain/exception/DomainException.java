// Radice della gerarchia delle eccezioni di dominio dell'applicazione. Tutte le eccezioni di questo
// package la estendono, così GlobalExceptionHandler può tradurre ciascuna in un ProblemDetail (RFC 7807)
// con lo status HTTP e lo slug corretti.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Classe base astratta delle eccezioni di dominio. È unchecked (estende {@link RuntimeException}) così
 * da attraversare i service senza dichiarazioni {@code throws} e da provocare il rollback delle
 * transazioni Spring.
 */
public abstract class DomainException extends RuntimeException {
    /**
     * @param message descrizione dell'errore
     */
    protected DomainException(String message) {
        super(message);
    }

    /**
     * @param message descrizione dell'errore
     * @param cause   eccezione tecnica che l'ha provocato
     */
    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
