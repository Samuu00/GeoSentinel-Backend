// Oggetti di trasferimento tra controller e service per le segnalazioni di pericolo: il comando di
// creazione, che contiene già la geometria JTS, e la vista restituita dal service, senza entità né
// geometrie JTS.
package it.unical.geosentinel.trail.application.dto;

import it.unical.geosentinel.shared.web.geo.GeoPointDto;
import it.unical.geosentinel.shared.web.geo.GeometryMapper;
import it.unical.geosentinel.trail.domain.HazardReport;
import it.unical.geosentinel.trail.domain.HazardStatus;
import it.unical.geosentinel.trail.domain.HazardType;
import it.unical.geosentinel.trail.domain.Severity;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.UUID;

/**
 * Contenitore dei record usati dal service delle segnalazioni.
 */
public class HazardCommands {
    /**
     * Dati per creare una segnalazione.
     *
     * @param trailId     sentiero di riferimento, facoltativo
     * @param type        tipo di pericolo
     * @param severity    gravità
     * @param description descrizione facoltativa
     * @param location    posizione con SRID 4326
     */
    public record CreateHazardReportCommand(UUID trailId, HazardType type, Severity severity, String description,  Point location) {}
    /**
     * Vista in sola lettura di una segnalazione, restituita dal service.
     *
     * @param id          identificativo
     * @param reporterId  utente che l'ha inviata
     * @param trailId     sentiero di riferimento, può essere {@code null}
     * @param type        tipo di pericolo
     * @param severity    gravità
     * @param description descrizione, può essere {@code null}
     * @param location    posizione come latitudine e longitudine
     * @param status      stato corrente
     * @param createdAt   istante di creazione
     */
    public record HazardView(
            UUID id,
            UUID reporterId,
            UUID trailId,
            HazardType type,
            Severity severity,
            String description,
            GeoPointDto location,
            HazardStatus status,
            Instant createdAt
        ) {
        /**
         * Costruisce la vista a partire dall'entità.
         *
         * @param h   segnalazione
         * @param geo mapper delle geometrie
         * @return la vista
         */
        public static HazardView from(HazardReport h, GeometryMapper geo) {
            return new HazardView(
                    h.getId(),
                    h.getReporterId(),
                    h.getTrail() != null ? h.getTrail().getId() : null,
                    h.getType(),
                    h.getSeverity(),
                    h.getDescription(),
                    geo.toDto(h.getLocation()),
                    h.getStatus(),
                    h.getCreatedAt()
            );
        }
    }
}
