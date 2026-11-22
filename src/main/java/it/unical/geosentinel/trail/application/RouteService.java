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
 * Percorso A→B a piedi (ADR-0028): lo calcola il motore ({@link RoutingEngine}, GraphHopper sull'estratto OSM) su
 * sentieri, mulattiere e strade, lontano dai sentieri CLOSED del parco; i sentieri del parco che il percorso segue
 * si ricavano in PostGIS. Le coordinate di partenza e arrivo sono posizioni degli utenti: non si loggano.
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
        // ~5 m come i sentieri della mappa (ADR-0020)
        Coordinate[] all = route.coordinates().stream().map(c -> new Coordinate(c.get(0), c.get(1))).toArray(Coordinate[]::new);
        Coordinate[] simplified = DouglasPeuckerSimplifier.simplify(GEOMETRY.createLineString(all), SIMPLIFY_TOLERANCE_DEGREES).getCoordinates();
        List<List<Double>> coordinates = Arrays.stream(simplified).map(c -> List.of(c.x, c.y)).toList();
        return new Route(GeoJsonLineString.of(coordinates), route.distanceM(), trailsAlong(simplified));
    }

    // Le query del repository hanno già la loro transazione di sola lettura (Spring Data)
    private List<UUID> trailsAlong(Coordinate[] path) {
        String wkt = Arrays.stream(path).map(c -> c.x + " " + c.y).collect(Collectors.joining(", ", "LINESTRING(", ")"));
        return trailRepository.findTrailIdsAlong(wkt, TRAIL_MATCH_TOLERANCE_METERS, TRAIL_MATCH_MIN_OVERLAP_METERS)
                .stream().map(UUID::fromString).toList();
    }
}
