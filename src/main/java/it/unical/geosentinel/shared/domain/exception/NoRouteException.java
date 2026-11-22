package it.unical.geosentinel.shared.domain.exception;

// Il motore non trova un percorso a piedi: un punto fuori dalla rete OSM o due punti non collegati (422 no-route)
public class NoRouteException extends DomainException {
    public NoRouteException() {
        super("No walking route between the two points: both must be inside the routing area and connected");
    }
}
