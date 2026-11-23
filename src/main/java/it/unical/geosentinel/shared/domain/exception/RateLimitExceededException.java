// Eccezione lanciata dai service quando un utente supera il limite di tentativi di un'operazione
// sensibile (ad esempio i codici della verifica in due passaggi). GlobalExceptionHandler risponde 429
// con l'header Retry-After calcolato dall'attesa contenuta nell'eccezione.
package it.unical.geosentinel.shared.domain.exception;

import java.time.Duration;

/**
 * Segnala che sono stati superati i tentativi consentiti e indica dopo quanto tempo il client può
 * riprovare.
 */
public class RateLimitExceededException extends DomainException {
    /** Attesa minima prima del prossimo tentativo. */
    private final Duration retryAfter;

    /**
     * @param retryAfter tempo che il client deve attendere prima di riprovare
     */
    public RateLimitExceededException(Duration retryAfter) {
        super("Too many attempts");
        this.retryAfter = retryAfter;
    }

    /**
     * @return l'attesa prima del prossimo tentativo, usata per l'header {@code Retry-After}
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
