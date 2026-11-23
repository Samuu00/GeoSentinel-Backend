// Oggetti di trasferimento tra controller e service per i sentieri: i comandi di creazione e modifica,
// i parametri delle ricerche geografiche e le due viste restituite, il dettaglio completo e il riepilogo
// con il tracciato semplificato usato dalla mappa.
package it.unical.geosentinel.trail.application.dto;

import it.unical.geosentinel.shared.web.geo.GeoJsonLineString;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import it.unical.geosentinel.trail.domain.Difficulty;
import it.unical.geosentinel.trail.domain.TrailStatus;
import it.unical.geosentinel.trail.domain.Trail;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;

import java.util.UUID;

/**
 * Contenitore dei record usati dal service dei sentieri.
 */
public class TrailCommands {
    /**
     * Dati per creare un sentiero.
     *
     * @param name        nome
     * @param description descrizione facoltativa
     * @param difficulty  difficoltà
     * @param path        tracciato con SRID 4326
     */
    public record CreateTrailCommand(String name, String description, Difficulty difficulty, LineString path) {}
    /**
     * Dati per modificare un sentiero; sostituiscono tutti i valori precedenti.
     *
     * @param name        nome
     * @param description descrizione facoltativa
     * @param difficulty  difficoltà
     * @param path        tracciato con SRID 4326
     */
    public record UpdateTrailCommand(String name, String description, Difficulty difficulty, LineString path) {}
    /**
     * Parametri della ricerca dei sentieri attorno a un punto. Raggio e numero di risultati vengono
     * limitati dal service.
     *
     * @param lon          longitudine del centro
     * @param lat          latitudine del centro
     * @param radiusMeters raggio di ricerca in metri
     * @param limit        numero massimo di risultati richiesto
     */
    public record GeoQuery(double lon, double lat, double radiusMeters, int limit) {}
    /**
     * Rettangolo geografico (bounding box) della porzione di mappa visibile.
     *
     * @param minLon longitudine del lato ovest
     * @param minLat latitudine del lato sud
     * @param maxLon longitudine del lato est
     * @param maxLat latitudine del lato nord
     */
    public record BoundingBox(double minLon, double minLat, double maxLon, double maxLat) {
        /**
         * Controlla che i limiti siano ordinati e dentro i range validi delle coordinate.
         *
         * @throws IllegalArgumentException se il rettangolo è vuoto, rovesciato o fuori range
         */
        public BoundingBox {
            if (minLon >= maxLon || minLat >= maxLat
                    || minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90) {
                throw new IllegalArgumentException("Invalid bounding box");
            }
        }
    }
    /**
     * Dettaglio completo di un sentiero, con il tracciato intero. Viene salvato in JSON nella cache Redis
     * dei sentieri, quindi contiene solo tipi serializzabili e nessuna geometria JTS.
     *
     * @param id          identificativo
     * @param name        nome
     * @param description descrizione, può essere {@code null}
     * @param difficulty  difficoltà
     * @param status      stato
     * @param path        tracciato completo in GeoJSON
     * @param lengthM     lunghezza in metri calcolata dal database
     * @param version     versione per il lock ottimistico
     */
    public record TrailDetail(
            UUID id,
            String name,
            String description,
            Difficulty difficulty,
            TrailStatus status,
            GeoJsonLineString path,
            Double lengthM,
            Long version
    ) {
        /**
         * Costruisce il dettaglio a partire dall'entità.
         *
         * @param trail sentiero
         * @param geo   mapper delle geometrie
         * @return il dettaglio
         */
        public static TrailDetail from(Trail trail, GeometryMapper geo) {
            return new TrailDetail(
                    trail.getId(),
                    trail.getName(),
                    trail.getDescription(),
                    trail.getDifficulty(),
                    trail.getStatus(),
                    geo.toDto(trail.getPath()),
                    trail.getLengthM(),
                    trail.getVersion()
            );
        }
    }
    /**
     * Riepilogo di un sentiero per liste e mappa. Il tracciato è semplificato: basta per disegnare il
     * sentiero sulla mappa e alleggerisce la risposta, mentre quello completo è nel dettaglio.
     *
     * @param id         identificativo
     * @param name       nome
     * @param difficulty difficoltà
     * @param status     stato
     * @param lengthM    lunghezza in metri del tracciato completo
     * @param path       tracciato semplificato in GeoJSON
     */
    public record TrailSummary(UUID id, String name, Difficulty difficulty, TrailStatus status, Double lengthM,
                               GeoJsonLineString path) {
        /**
         * Tolleranza di Douglas-Peucker in gradi, perché la geometria è in SRID 4326: circa 5 metri a
         * queste latitudini, sotto la risoluzione della mappa.
         */
        static final double SIMPLIFY_TOLERANCE_DEGREES = 0.00005;

        /**
         * Costruisce il riepilogo semplificando il tracciato.
         *
         * @param trail sentiero
         * @param geo   mapper delle geometrie
         * @return il riepilogo
         */
        public static TrailSummary from(Trail trail, GeometryMapper geo) {
            // Douglas-Peucker su una LineString conserva sempre i due estremi, quindi il risultato ha almeno
            // due punti ed è ancora una LineString valida.
            LineString simplified = (LineString) DouglasPeuckerSimplifier.simplify(trail.getPath(), SIMPLIFY_TOLERANCE_DEGREES);
            return new TrailSummary(
                    trail.getId(),
                    trail.getName(),
                    trail.getDifficulty(),
                    trail.getStatus(),
                    trail.getLengthM(),
                    geo.toDto(simplified)
            );
        }
    }

}
