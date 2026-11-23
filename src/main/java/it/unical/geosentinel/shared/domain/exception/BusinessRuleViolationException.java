// Eccezione generica per le violazioni delle regole di business (ad esempio una transizione di stato non
// ammessa o date di prenotazione incoerenti). GlobalExceptionHandler la traduce in un 422 business-rule
// con il messaggio dell'eccezione nel campo detail.
package it.unical.geosentinel.shared.domain.exception;

/**
 * Segnala che la richiesta è sintatticamente valida ma viola una regola del dominio.
 * È l'eccezione da usare quando nessuna delle eccezioni più specifiche descrive il caso.
 */
public class BusinessRuleViolationException extends DomainException {
    /**
     * @param message spiegazione della regola violata, mostrata al client
     */
    public BusinessRuleViolationException(String message) {
        super(message);
    }

    /**
     * @param message spiegazione della regola violata, mostrata al client
     * @param cause   eccezione originale che ha fatto emergere la violazione
     */
    public BusinessRuleViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
