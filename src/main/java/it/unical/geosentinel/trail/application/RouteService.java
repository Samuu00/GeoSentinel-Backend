package it.unical.geosentinel.trail.application;

import it.unical.geosentinel.shared.domain.exception.NoRouteException;
import it.unical.geosentinel.shared.web.geo.GeoJsonLineString;
import it.unical.geosentinel.trail.domain.TrailStatus;
import it.unical.geosentinel.trail.infrastructure.persistence.TrailRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Percorso A→B sui sentieri aperti (ADR-0027). La rete si costruisce alla prima richiesta e resta in memoria finché
 * un sentiero non cambia ({@link TrailsChanged}, dopo il commit). Le coordinate di partenza e arrivo sono posizioni
 * degli utenti: non si loggano.
 */
@Service
public class RouteService {

    /** Pubblicato da TrailService quando un sentiero nasce, cambia percorso o stato. */
    public record TrailsChanged() {}

    public record Route(GeoJsonLineString path, double lengthM, List<UUID> trailIds) {}

    private static final double SIMPLIFY_TOLERANCE_DEGREES = 0.00005;
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    private final TrailRepository trailRepository;
    // guarded by this: costruzione e invalidazione non si sovrappongono, quindi una rete letta prima del commit
    // non può sopravvivere all'invalidazione che segue
    private TrailNetwork network;

    public RouteService(TrailRepository trailRepository) {
        this.trailRepository = trailRepository;
    }

    @Transactional(readOnly = true)
    public Route findRoute(double fromLon, double fromLat, double toLon, double toLat) {
        TrailNetwork.Route route = network().route(fromLon, fromLat, toLon, toLat).orElseThrow(NoRouteException::new);
        // ~5 m come i sentieri della mappa (ADR-0020): un percorso lungo passava da 12k a poche centinaia di punti
        Coordinate[] all = route.path().toArray(Coordinate[]::new);
        Coordinate[] simplified = DouglasPeuckerSimplifier.simplify(GEOMETRY.createLineString(all), SIMPLIFY_TOLERANCE_DEGREES).getCoordinates();
        List<List<Double>> coordinates = Arrays.stream(simplified).map(c -> List.of(c.x, c.y)).toList();
        return new Route(GeoJsonLineString.of(coordinates), route.lengthM(), route.trailIds());
    }

    // La rete si prepara all'avvio: la prima richiesta non aspetta la costruzione
    @Async
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void warmUp() {
        network();
    }

    @TransactionalEventListener
    public synchronized void onTrailsChanged(TrailsChanged event) {
        network = null;
    }

    private synchronized TrailNetwork network() {
        if (network == null) {
            network = TrailNetwork.of(trailRepository.findByStatusNot(TrailStatus.CLOSED).stream()
                    .map(trail -> new TrailNetwork.TrailPath(trail.getId(), trail.getPath()))
                    .toList());
        }
        return network;
    }
}
