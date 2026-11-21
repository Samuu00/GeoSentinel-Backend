package it.unical.geosentinel.shared.domain.exception;

// Partenza o arrivo lontani dai sentieri aperti, oppure su sentieri non collegati tra loro (422 no-route)
public class NoRouteException extends DomainException {
    public NoRouteException() {
        super("No route on open trails between the two points: each must be within 50 km of a trail, on connected trails");
    }
}
