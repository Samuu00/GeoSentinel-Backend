package it.unical.geosentinel.trail.application;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * La rete dei sentieri aperti per il percorso A→B (ADR-0027), in memoria: un nodo per ogni vertice, archi lungo
 * ciascun sentiero e archi "di giunzione" tra vertici di sentieri diversi entro {@link #JOIN_METERS} (in OSM i
 * sentieri che si incrociano condividono un vertice; i capi che quasi si toccano restano a pochi metri). Immutabile:
 * quando un sentiero cambia se ne costruisce un'altra. Circa 67k vertici per la Calabria.
 */
final class TrailNetwork {
    // Misurato sui dati della Calabria (21/11/2026): da 5 a 15 m i gruppi di sentieri collegati passano da 58 a 57,
    // oltre cambia poco. 15 m prende anche i capi digitalizzati un po' storti
    static final double JOIN_METERS = 15;
    // Partenza e arrivo si agganciano, per ogni gruppo di sentieri collegati, al vertice più vicino entro questa distanza:
    // 50 km come il resto delle ricerche. Il tratto fuori dai sentieri l'app lo disegna tratteggiato (come Google Maps)
    static final double MAX_SNAP_METERS = 50_000;
    // Un metro fuori dai sentieri costa come dieci sopra: serve solo per raggiungere la rete, non per tagliare. Con 3,
    // e l'aggancio fino a 50 km, un percorso di 70 km in linea d'aria usciva dai sentieri a metà. E la linea d'aria
    // verso un sentiero isolato vicino
    // all'arrivo batterebbe il percorso sui sentieri collegati.
    // ponytail: costo fisso, senza pendenza né ostacoli; da rivedere se arrivano le quote
    static final double OFF_TRAIL_FACTOR = 10;
    private static final double EARTH_RADIUS_M = 6_371_008.8;
    private static final double METERS_PER_DEGREE = Math.toRadians(1) * EARTH_RADIUS_M;

    record TrailPath(UUID trailId, LineString path) {}

    /** Il percorso: coordinate [lon, lat] dalla partenza all'arrivo, lunghezza e sentieri percorsi in ordine. */
    record Route(List<Coordinate> path, double lengthM, List<UUID> trailIds) {}

    private record Edge(int to, double meters, UUID trailId) {}

    private final double[] lon;
    private final double[] lat;
    private final List<List<Edge>> edges;
    // il gruppo di sentieri collegati di ogni vertice
    private final int[] component;
    private final STRtree index = new STRtree();

    private TrailNetwork(int size) {
        lon = new double[size];
        lat = new double[size];
        edges = new ArrayList<>(size);
        component = new int[size];
    }

    static TrailNetwork of(List<TrailPath> trails) {
        int size = trails.stream().mapToInt(t -> t.path().getNumPoints()).sum();
        TrailNetwork network = new TrailNetwork(size);
        UUID[] trailOf = new UUID[size];
        int node = 0;
        for (TrailPath trail : trails) {
            Coordinate[] coordinates = trail.path().getCoordinates();
            for (int i = 0; i < coordinates.length; i++, node++) {
                network.lon[node] = coordinates[i].x;
                network.lat[node] = coordinates[i].y;
                network.edges.add(new ArrayList<>(2));
                trailOf[node] = trail.trailId();
                network.index.insert(new Envelope(coordinates[i]), node);
                if (i > 0) network.link(node - 1, node, trail.trailId());
            }
        }
        network.index.build();
        // Giunzioni: ogni coppia di vertici di sentieri diversi abbastanza vicini, una volta sola (j > i). Sullo stesso
        // sentiero solo i vertici coincidenti (anelli, 11 in Calabria): entro 15 m taglierebbe i tornanti
        for (int i = 0; i < size; i++) {
            for (int j : network.near(i, JOIN_METERS)) {
                boolean sameTrail = trailOf[i].equals(trailOf[j]);
                boolean samePoint = network.lon[i] == network.lon[j] && network.lat[i] == network.lat[j];
                if (j > i && (!sameTrail || samePoint)) network.link(i, j, null);
            }
        }
        network.labelComponents();
        return network;
    }

    private void labelComponents() {
        Arrays.fill(component, -1);
        int label = 0;
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        for (int i = 0; i < component.length; i++) {
            if (component[i] >= 0) continue;
            component[i] = label;
            stack.push(i);
            while (!stack.isEmpty()) {
                for (Edge edge : edges.get(stack.pop())) {
                    if (component[edge.to()] < 0) {
                        component[edge.to()] = label;
                        stack.push(edge.to());
                    }
                }
            }
            label++;
        }
    }

    Optional<Route> route(double fromLon, double fromLat, double toLon, double toLat) {
        // Aggancio al vertice più vicino di ogni gruppo di sentieri collegati entro MAX_SNAP_METERS, non solo al più
        // vicino in assoluto: quello può stare su un gruppo isolato mentre poco più in là ce n'è uno collegato (provato
        // sulla Sila, 21/11). Uno per gruppo: con tutti i vertici il percorso taglierebbe in linea d'aria.
        // Il tratto in linea d'aria fino al sentiero entra nel costo, così vince l'aggancio più conveniente
        double[] distance = new double[lon.length];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        int[] previous = new int[lon.length];
        Edge[] via = new Edge[lon.length];
        boolean[] source = new boolean[lon.length];
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (int node : within(fromLon, fromLat)) {
            distance[node] = OFF_TRAIL_FACTOR * meters(fromLon, fromLat, lon[node], lat[node]);
            source[node] = true;
            queue.add(new double[]{distance[node], node});
        }
        double[] toTarget = new double[lon.length];
        Arrays.fill(toTarget, Double.NaN);
        for (int node : within(toLon, toLat)) toTarget[node] = OFF_TRAIL_FACTOR * meters(toLon, toLat, lon[node], lat[node]);

        // Dijkstra con un arrivo virtuale: un vertice d'arrivo costa la sua distanza più la linea d'aria fino al punto
        int end = -1;
        double best = Double.POSITIVE_INFINITY;
        while (!queue.isEmpty()) {
            double[] head = queue.poll();
            int node = (int) head[1];
            if (head[0] > distance[node]) continue;
            if (head[0] >= best) break;
            if (!Double.isNaN(toTarget[node]) && distance[node] + toTarget[node] < best) {
                best = distance[node] + toTarget[node];
                end = node;
            }
            for (Edge edge : edges.get(node)) {
                double candidate = distance[node] + edge.meters();
                if (candidate < distance[edge.to()]) {
                    distance[edge.to()] = candidate;
                    previous[edge.to()] = node;
                    via[edge.to()] = edge;
                    source[edge.to()] = false;
                    queue.add(new double[]{candidate, edge.to()});
                }
            }
        }
        if (end < 0) return Optional.empty();

        List<Coordinate> path = new ArrayList<>();
        List<UUID> backwards = new ArrayList<>();
        int start = end;
        for (int node = end; ; node = previous[node]) {
            start = node;
            Coordinate c = new Coordinate(lon[node], lat[node]);
            // le giunzioni da 0 m (vertice condiviso in OSM) lascerebbero due punti uguali di fila
            if (path.isEmpty() || !path.getLast().equals2D(c)) path.add(c);
            if (source[node]) break;
            if (via[node].trailId() != null) backwards.add(via[node].trailId());
        }
        if (path.size() == 1) path.add(path.getFirst().copy()); // partenza e arrivo sullo stesso vertice
        List<UUID> trails = List.copyOf(new LinkedHashSet<>(backwards.reversed()));
        // la lunghezza è quella sui sentieri, senza i due tratti in linea d'aria
        double startMeters = OFF_TRAIL_FACTOR * meters(fromLon, fromLat, lon[start], lat[start]);
        return Optional.of(new Route(path.reversed(), distance[end] - startMeters, trails));
    }

    private void link(int a, int b, UUID trailId) {
        double meters = meters(lon[a], lat[a], lon[b], lat[b]);
        edges.get(a).add(new Edge(b, meters, trailId));
        edges.get(b).add(new Edge(a, meters, trailId));
    }

    private List<Integer> near(int node, double radiusMeters) {
        List<Integer> result = new ArrayList<>();
        for (Object item : index.query(box(lon[node], lat[node], radiusMeters))) {
            int other = (Integer) item;
            if (other != node && meters(lon[node], lat[node], lon[other], lat[other]) <= radiusMeters) result.add(other);
        }
        return result;
    }

    // per ogni gruppo di sentieri collegati, il suo vertice più vicino al punto entro MAX_SNAP_METERS
    private Collection<Integer> within(double pointLon, double pointLat) {
        Map<Integer, Integer> nearestByComponent = new HashMap<>();
        Map<Integer, Double> metersByComponent = new HashMap<>();
        for (Object item : index.query(box(pointLon, pointLat, MAX_SNAP_METERS))) {
            int node = (Integer) item;
            double m = meters(pointLon, pointLat, lon[node], lat[node]);
            if (m <= MAX_SNAP_METERS && m < metersByComponent.getOrDefault(component[node], Double.POSITIVE_INFINITY)) {
                metersByComponent.put(component[node], m);
                nearestByComponent.put(component[node], node);
            }
        }
        return nearestByComponent.values();
    }

    private static Envelope box(double pointLon, double pointLat, double radiusMeters) {
        double dLat = radiusMeters / METERS_PER_DEGREE;
        double dLon = dLat / Math.max(0.01, Math.cos(Math.toRadians(pointLat)));
        return new Envelope(pointLon - dLon, pointLon + dLon, pointLat - dLat, pointLat + dLat);
    }

    // Equirettangolare: tra vertici vicini l'errore rispetto all'ellissoide è sotto lo 0,5%
    static double meters(double lon1, double lat1, double lon2, double lat2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * EARTH_RADIUS_M;
    }
}
