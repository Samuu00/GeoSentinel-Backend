package it.unical.geosentinel.trail.application;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Sila, circa 39.3 N: 0.001 gradi di latitudine sono 111 m, di longitudine circa 86 m
class TrailNetworkTest {
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private final UUID west = UUID.randomUUID();
    private final UUID east = UUID.randomUUID();
    private final UUID north = UUID.randomUUID();
    private final UUID island = UUID.randomUUID();

    // west e east si toccano a 5 m (sotto JOIN_METERS); north parte dal vertice in mezzo a east; island è a 1 km
    private final TrailNetwork network = TrailNetwork.of(List.of(
            trail(west, 16.500, 39.300, 16.505, 39.300, 16.510, 39.300),
            trail(east, 16.51005, 39.300, 16.515, 39.300, 16.520, 39.300),
            trail(north, 16.515, 39.300, 16.515, 39.305),
            trail(island, 16.500, 39.310, 16.505, 39.310)
    ));

    @Test
    void routeCrossesJunctionsAndListsTrailsInOrder() {
        TrailNetwork.Route route = network.route(16.500, 39.2999, 16.515, 39.305).orElseThrow();

        assertThat(route.trailIds()).containsExactly(west, east, north);
        assertThat(route.path().getFirst()).isEqualTo(new Coordinate(16.500, 39.300));
        assertThat(route.path().getLast()).isEqualTo(new Coordinate(16.515, 39.305));
        // 15 * 0.001 gradi di longitudine (circa 1290 m) più 0.005 di latitudine (circa 556 m)
        assertThat(route.lengthM()).isBetween(1800.0, 1900.0);
    }

    @Test
    void loopTrailIsClosedAtItsStartVertex() {
        UUID loop = UUID.randomUUID();
        // anello: parte e torna a 16.600 39.300, passando lontano a est
        TrailNetwork ring = TrailNetwork.of(List.of(
                trail(loop, 16.600, 39.300, 16.610, 39.300, 16.610, 39.310, 16.601, 39.300, 16.600, 39.300)));

        TrailNetwork.Route route = ring.route(16.600, 39.300, 16.601, 39.300).orElseThrow();

        // circa 86 m chiudendo l'anello, non il giro intero di oltre 2 km
        assertThat(route.lengthM()).isLessThan(100.0);
    }

    @Test
    void snapSkipsANearerIsolatedTrailWhenAConnectedOneIsInReach() {
        UUID main = UUID.randomUUID();
        UUID isolated = UUID.randomUUID();
        // l'arrivo è a 111 m dal sentiero isolato e a 1,1 km da quello principale; la partenza è sul principale,
        // a 3,5 km dall'isolato: i 3,5 km fuori sentiero costano più di 3,4 km di sentiero e 1,1 km fuori
        TrailNetwork twoGroups = TrailNetwork.of(List.of(
                trail(main, 16.600, 39.300, 16.620, 39.300, 16.640, 39.300),
                trail(isolated, 16.600, 39.311, 16.601, 39.311)));

        TrailNetwork.Route route = twoGroups.route(16.640, 39.300, 16.600, 39.310).orElseThrow();

        assertThat(route.trailIds()).containsExactly(main);
        assertThat(route.path().getLast()).isEqualTo(new Coordinate(16.600, 39.300));
    }

    @Test
    void pointFarFromEveryTrailHasNoRoute() {
        // oltre i 50 km da ogni sentiero
        assertThat(network.route(16.500, 39.300, 17.500, 40.000)).isEmpty();
    }

    @Test
    void sameStartAndEndGiveATwoPointPath() {
        TrailNetwork.Route route = network.route(16.505, 39.300, 16.505, 39.300).orElseThrow();

        assertThat(route.path()).hasSize(2);
        assertThat(route.lengthM()).isZero();
        assertThat(route.trailIds()).isEmpty();
    }

    private static TrailNetwork.TrailPath trail(UUID id, double... lonLat) {
        Coordinate[] coordinates = new Coordinate[lonLat.length / 2];
        for (int i = 0; i < coordinates.length; i++) coordinates[i] = new Coordinate(lonLat[2 * i], lonLat[2 * i + 1]);
        LineString path = GEOMETRY.createLineString(coordinates);
        return new TrailNetwork.TrailPath(id, path);
    }
}
