package it.unical.geosentinel.trail.application;

import it.unical.geosentinel.shared.domain.exception.RoutingUnavailableException;

import java.util.List;
import java.util.Optional;

/**
 * Porta verso il motore di percorso a piedi (GraphHopper sull'estratto OSM, ADR-0028). Le coordinate sono posizioni
 * degli utenti: l'adattatore non le logga.
 */
public interface RoutingEngine {

    /** Il tracciato in coppie {@code [lon, lat]} dalla partenza all'arrivo, con la lunghezza in metri. */
    record EngineRoute(List<List<Double>> coordinates, double distanceM) {}

    /**
     * Il percorso a piedi da A a B solo sui sentieri (i tratti degli itinerari escursionistici), fuori dalle aree
     * vietate. Partenza e arrivo si agganciano al sentiero più vicino, anche lontano: la distanza la controlla chi chiama.
     *
     * @param avoidGeoJson geometria GeoJSON (Polygon o MultiPolygon, {@code [lon, lat]}) da non attraversare, o null
     * @return vuoto se il motore non trova un percorso (un punto fuori dalla rete o due punti non collegati)
     * @throws RoutingUnavailableException se il motore non risponde
     */
    Optional<EngineRoute> route(double fromLon, double fromLat, double toLon, double toLat, String avoidGeoJson);
}
