package it.unical.geosentinel.shared.domain.exception;

// 503: il motore di percorso (GraphHopper, ADR-0028) non risponde. Qui perché l'handler è in shared
public class RoutingUnavailableException extends DomainException {
    public RoutingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
