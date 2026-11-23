// Service che calcola il percorso a piedi tra due punti (A e B) restando sui sentieri del parco. Delega
// il calcolo al RoutingEngine, poi verifica che partenza e arrivo siano abbastanza vicini a un sentiero,
// semplifica il tracciato e ricava con PostGIS quali sentieri del parco il percorso segue.
package it.unical.geosentinel.trail.application;

import it.unical.geosentinel.shared.domain.exception.NoRouteException;
import it.unical.geosentinel.shared.web.geo.GeoJsonLineString;
import it.unical.geosentinel.trail.infrastructure.persistence.TrailRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Calcolo del percorso tra due punti sui sentieri. Il tracciato lo produce il {@link RoutingEngine} sugli
 * itinerari escursionistici, evitando i sentieri {@code CLOSED}; partenza e arrivo devono trovarsi entro
 * 1 km da un sentiero, altrimenti il percorso non esiste. Le coordinate di partenza e arrivo sono
 * posizioni degli utenti e non vengono mai scritte nei log.
 */
@Service
public class RouteService {

    /**
     * Percorso restituito al controller.
     *
     * @param path     tracciato semplificato in GeoJSON
     * @param lengthM  lunghezza in metri calcolata dal motore sul tracciato completo
     * @param trailIds sentieri del parco seguiti dal percorso, nell'ordine in cui si incontrano
     */
    public record Route(GeoJsonLineString path, double lengthM, List<UUID> trailIds) {}

    /**
     * Tolleranza della semplificazione Douglas-Peucker, in gradi perché la geometria è in SRID 4326:
     * circa 5 metri a queste latitudini, la stessa usata per i sentieri della mappa.
     */
    private static final double SIMPLIFY_TOLERANCE_DEGREES = 0.00005;
    /**
     * Raggio in metri dell'area vietata attorno a un sentiero chiuso. Anche una strada che lo incrocia
     * resta bloccata nel tratto che lo attraversa, perché GraphHopper esclude l'intero arco del grafo.
     */
    private static final double CLOSED_BUFFER_METERS = 10;
    /**
     * Un sentiero è considerato seguito se il percorso gli resta entro questa distanza, in metri, per
     * almeno {@link #TRAIL_MATCH_MIN_OVERLAP_METERS}. Serve una tolleranza perché le geometrie di
     * OpenStreetMap e quelle del database non coincidono al metro.
     */
    private static final double TRAIL_MATCH_TOLERANCE_METERS = 20;
    /** Tratto minimo in comune, in metri: un semplice incrocio con il sentiero non basta. */
    private static final double TRAIL_MATCH_MIN_OVERLAP_METERS = 50;
    /** Distanza massima in metri tra la partenza (o l'arrivo) e il punto del sentiero a cui si aggancia. */
    private static final double MAX_SNAP_METERS = 1_000;
    /** Raggio medio della Terra in metri, per la formula dell'haversine. */
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    /** Factory usata solo per la semplificazione in memoria: il risultato non viene salvato. */
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    private final TrailRepository trailRepository;
    private final RoutingEngine routingEngine;

    /**
     * @param trailRepository repository dei sentieri, per le aree chiuse e i sentieri seguiti
     * @param routingEngine   motore di calcolo dei percorsi
     */
    public RouteService(TrailRepository trailRepository, RoutingEngine routingEngine) {
        this.trailRepository = trailRepository;
        this.routingEngine = routingEngine;
    }

    /**
     * Calcola il percorso tra due punti. Il metodo non è transazionale di proposito: una transazione
     * attorno alla chiamata al motore terrebbe occupata una connessione del pool anche per 10 secondi.
     *
     * @param fromLon longitudine della partenza
     * @param fromLat latitudine della partenza
     * @param toLon   longitudine dell'arrivo
     * @param toLat   latitudine dell'arrivo
     * @return il percorso semplificato con lunghezza e sentieri seguiti
     * @throws NoRouteException se il motore non trova un percorso o se partenza o arrivo distano più di
     *         1 km dal sentiero
     * @throws it.unical.geosentinel.shared.domain.exception.RoutingUnavailableException se il motore non risponde
     */
    public Route findRoute(double fromLon, double fromLat, double toLon, double toLat) {
        String closedAreas = trailRepository.findClosedAreasGeoJson(CLOSED_BUFFER_METERS);
        RoutingEngine.EngineRoute route = routingEngine.route(fromLon, fromLat, toLon, toLat, closedAreas)
                .orElseThrow(NoRouteException::new);
        // Il motore aggancia partenza e arrivo al sentiero più vicino anche se è lontano: oltre
        // MAX_SNAP_METERS il punto non si considera sul sentiero e il percorso non esiste, perché si
        // cammina solo sui sentieri del parco.
        List<Double> first = route.coordinates().getFirst();
        List<Double> last = route.coordinates().getLast();
        if (distanceMeters(fromLon, fromLat, first.get(0), first.get(1)) > MAX_SNAP_METERS
                || distanceMeters(toLon, toLat, last.get(0), last.get(1)) > MAX_SNAP_METERS) {
            throw new NoRouteException();
        }
        // Semplificazione del tracciato per alleggerire la risposta, con la stessa tolleranza dei sentieri della mappa.
        Coordinate[] all = route.coordinates().stream().map(c -> new Coordinate(c.get(0), c.get(1))).toArray(Coordinate[]::new);
        Coordinate[] simplified = DouglasPeuckerSimplifier.simplify(GEOMETRY.createLineString(all), SIMPLIFY_TOLERANCE_DEGREES).getCoordinates();
        List<List<Double>> coordinates = Arrays.stream(simplified).map(c -> List.of(c.x, c.y)).toList();
        return new Route(GeoJsonLineString.of(coordinates), route.distanceM(), trailsAlong(simplified));
    }

    /**
     * Distanza tra due punti con la formula dell'haversine, che considera la Terra una sfera: per
     * distanze di qualche chilometro l'errore è di pochi metri.
     *
     * @return la distanza in metri
     */
    private static double distanceMeters(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.min(1, a)));
    }

    /**
     * Trova i sentieri del parco seguiti dal percorso, passando il tracciato a PostGIS come WKT. Non serve
     * una transazione esplicita: i metodi del repository Spring Data ne aprono già una di sola lettura.
     *
     * @param path tracciato semplificato
     * @return gli id dei sentieri, nell'ordine in cui il percorso li incontra
     */
    private List<UUID> trailsAlong(Coordinate[] path) {
        String wkt = Arrays.stream(path).map(c -> c.x + " " + c.y).collect(Collectors.joining(", ", "LINESTRING(", ")"));
        return trailRepository.findTrailIdsAlong(wkt, TRAIL_MATCH_TOLERANCE_METERS, TRAIL_MATCH_MIN_OVERLAP_METERS)
                .stream().map(UUID::fromString).toList();
    }
}
