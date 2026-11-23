// Eccezione della feature emergency: un operatore prova a prendere in carico un'allerta SOS a cui
// un collega ha già assegnato una squadra. Sta nel package shared perché è GlobalExceptionHandler
// (anch'esso in shared) a tradurla in una risposta 409 alert-already-taken.
package it.unical.geosentinel.shared.domain.exception;

import java.util.UUID;

/**
 * Segnala che un'allerta SOS è già stata presa in carico da un altro operatore.
 * Viene tradotta in 409 {@code alert-already-taken}, così l'app può rileggere l'allerta e mostrare chi
 * se ne sta occupando.
 */
public class AlertAlreadyTakenException extends DomainException {
    /**
     * @param alertId identificativo dell'allerta già presa in carico, riportato nel messaggio
     */
    public AlertAlreadyTakenException(UUID alertId) {
        super("SOS alert '%s' has already been taken by another operator".formatted(alertId));
    }
}
