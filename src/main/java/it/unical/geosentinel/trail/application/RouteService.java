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
 * Percorso A→B solo sui sentieri del parco (ADR-0028): lo calcola il motore ({@link RoutingEngine}, GraphHopper
 * sull'estratto OSM) sugli itinerari escursionistici, lontano dai sentieri CLOSED; partenza e arrivo entro 1 km da un
 * sentiero, altrimenti nessun percorso. I sentieri del parco che il percorso segue si ricavano in PostGIS. Le coordinate di partenza e arrivo sono posizioni degli utenti: non si loggano.
 */
@Service
public class RouteService {

    public record Route(GeoJsonLineString path, double lengthM, List<UUID> trailIds) {}

    private static final double SIMPLIFY_TOLERANCE_DEGREES = 0.00005;
    // Larghezza dell'area vietata attorno a un sentiero chiuso: anche una strada che lo incrocia resta bloccata
    // nel tratto che lo attraversa (GraphHopper esclude l'arco intero)
    private static final double CLOSED_BUFFER_METERS = 10;
    // Un sentiero è "seguito" se il percorso gli resta entro 20 m per almeno 50 m (OSM e il database non coincidono
    // al metro; un incrocio non basta)
    private static final double TRAIL_MATCH_TOLERANCE_METERS = 20;
    private static final double TRAIL_MATCH_MIN_OVERLAP_METERS = 50;
    // Distanza massima tra un punto (partenza o arrivo) e il sentiero su cui si aggancia
    private static final double MAX_SNAP_METERS = 1_000;
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    private final TrailRepository trailRepository;
    private final RoutingEngine routingEngine;

    public RouteService(TrailRepository trailRepository, RoutingEngine routingEngine) {
        this.trailRepository = trailRepository;
        this.routingEngine = routingEngine;
    }

    // Niente transazione attorno alla chiamata al motore: una connessione del pool resterebbe presa fino a 10 s
    public Route findRoute(double fromLon, double fromLat, double toLon, double toLat) {
        String closedAreas = trailRepository.findClosedAreasGeoJson(CLOSED_BUFFER_METERS);
        RoutingEngine.EngineRoute route = routingEngine.route(fromLon, fromLat, toLon, toLat, closedAreas)
                .orElseThrow(NoRouteException::new);
        // Il motore aggancia partenza e arrivo al sentiero più vicino anche se è lontano: oltre MAX_SNAP_METERS il punto
        // non è "sul sentiero" e il percorso non c'è (si cammina solo sui sentieri del parco)
        List<Double> first = route.coordinates().getFirst();
        List<Double> last = route.coordinates().getLast();
        if (distanceMeters(fromLon, fromLat, first.get(0), first.get(1)) > MAX_SNAP_METERS
                || distanceMeters(toLon, toLat, last.get(0), last.get(1)) > MAX_SNAP_METERS) {
            throw new NoRouteException();
        }
        // ~5 m come i sentieri della mappa (ADR-0020)
        Coordinate[] all = route.coordinates().stream().map(c -> new Coordinate(c.get(0), c.get(1))).toArray(Coordinate[]::new);
        Coordinate[] simplified = DouglasPeuckerSimplifier.simplify(GEOMETRY.createLineString(all), SIMPLIFY_TOLERANCE_DEGREES).getCoordinates();
        List<List<Double>> coordinates = Arrays.stream(simplified).map(c -> List.of(c.x, c.y)).toList();
        return new Route(GeoJsonLineString.of(coordinates), route.distanceM(), trailsAlong(simplified));
    }

    // Haversine: per distanze di qualche km l'errore è di pochi metri
    private static double distanceMeters(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.min(1, a)));
    }

    // Le query del repository hanno già la loro transazione di sola lettura (Spring Data)
    private List<UUID> trailsAlong(Coordinate[] path) {
        String wkt = Arrays.stream(path).map(c -> c.x + " " + c.y).collect(Collectors.joining(", ", "LINESTRING(", ")"));
        return trailRepository.findTrailIdsAlong(wkt, TRAIL_MATCH_TOLERANCE_METERS, TRAIL_MATCH_MIN_OVERLAP_METERS)
                .stream().map(UUID::fromString).toList();
    }
}
