package it.unical.geosentinel.shared.domain.exception;

// Nessun percorso sui sentieri: un punto a più di 1 km da ogni sentiero, o sentieri non collegati (422 no-route)
public class NoRouteException extends DomainException {
    public NoRouteException() {
        super("No route on the trails: both points must be within 1 km of a trail, on trails that connect");
    }
}
