// Porta (interfaccia) verso il motore esterno che calcola i percorsi a piedi. L'applicazione dipende solo
// da questa interfaccia; l'implementazione concreta, GraphHopperRoutingEngine, sta nel package
// infrastructure e interroga il servizio GraphHopper costruito su un estratto di OpenStreetMap.
package it.unical.geosentinel.trail.application;

import it.unical.geosentinel.shared.domain.exception.RoutingUnavailableException;

import java.util.List;
import java.util.Optional;

/**
 * Motore di calcolo dei percorsi a piedi. Le coordinate ricevute sono posizioni degli utenti, quindi
 * dati personali: le implementazioni non devono scriverle nei log.
 */
public interface RoutingEngine {

    /**
     * Risultato grezzo del motore.
     *
     * @param coordinates tracciato dalla partenza all'arrivo, come coppie {@code [lon, lat]}
     * @param distanceM   lunghezza del tracciato in metri
     */
    record EngineRoute(List<List<Double>> coordinates, double distanceM) {}

    /**
     * Calcola il percorso a piedi tra due punti usando solo i sentieri, cioè i tratti che fanno parte di
     * itinerari escursionistici, senza attraversare le aree vietate. Il motore aggancia partenza e arrivo
     * al sentiero più vicino anche se è molto lontano: controllare quella distanza è compito del chiamante.
     *
     * @param fromLon      longitudine della partenza
     * @param fromLat      latitudine della partenza
     * @param toLon        longitudine dell'arrivo
     * @param toLat        latitudine dell'arrivo
     * @param avoidGeoJson geometria GeoJSON (Polygon o MultiPolygon, coordinate {@code [lon, lat]}) da non
     *                     attraversare, oppure {@code null} se non ci sono aree vietate
     * @return il percorso, oppure vuoto se il motore non lo trova (un punto fuori dalla rete o due punti
     *         non collegati)
     * @throws RoutingUnavailableException se il motore non risponde
     */
    Optional<EngineRoute> route(double fromLon, double fromLat, double toLon, double toLat, String avoidGeoJson);
}
