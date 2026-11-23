// Eccezione di dominio lanciata in registrazione quando l'utente non ha l'età minima richiesta
// dall'articolo 8 del GDPR per dare da solo il consenso al trattamento dei dati.
// La risposta è un 422 age-requirement-not-met con un messaggio neutro che non rivela la soglia.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Segnala che la data di nascita indicata è sotto la soglia di età prevista dal GDPR.
 * GlobalExceptionHandler la traduce in 422 {@code age-requirement-not-met} senza riportare il messaggio
 * originale, così il client non scopre quale sia l'età minima.
 */
public class AgeRequirementNotMetException extends DomainException {
    /**
     * @param message descrizione interna dell'errore (non esposta al client)
     */
    public AgeRequirementNotMetException(String message) {
        super(message);
    }
}
