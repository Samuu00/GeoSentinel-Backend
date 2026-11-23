// Oggetti di trasferimento tra controller e service per i punti di interesse: il comando di creazione,
// con la geometria JTS, e la vista restituita dal service.
package it.unical.geosentinel.trail.application.dto;

import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import it.unical.geosentinel.trail.domain.Poi;
import it.unical.geosentinel.trail.domain.PoiType;
import org.locationtech.jts.geom.Point;

import java.util.UUID;

/**
 * Contenitore dei record usati dal service dei POI.
 */
public class PoiCommands {
    /**
     * Dati per creare un POI.
     *
     * @param trailId  sentiero di appartenenza, {@code null} per un POI indipendente
     * @param name     nome
     * @param type     categoria
     * @param location posizione con SRID 4326
     */
    public record CreatePoiCommand(UUID trailId, String name, PoiType type, Point location) {}
    /**
     * Vista in sola lettura di un POI.
     *
     * @param id       identificativo
     * @param trailId  sentiero di appartenenza, può essere {@code null}
     * @param name     nome
     * @param type     categoria
     * @param location posizione come latitudine e longitudine
     */
    public record PoiView(UUID id, UUID trailId, String name, PoiType type, GeoPointDto location) {
        /**
         * Costruisce la vista a partire dall'entità.
         *
         * @param poi POI
         * @param geo mapper delle geometrie
         * @return la vista
         */
        public static PoiView from(Poi poi, GeometryMapper geo) {
            return new PoiView(poi.getId(), poi.getTrail() != null ? poi.getTrail().getId() : null, poi.getName(), poi.getType(), geo.toDto(poi.getLocation()));
        }
    }
}
