// Repository Spring Data JPA delle segnalazioni di pericolo. Oltre ai metodi derivati dal nome contiene
// query native PostGIS per le ricerche geografiche: le distanze si calcolano sul tipo geography, in
// metri, e sfruttano gli indici GiST definiti sulle espressioni geography.
package it.unical.geosentinel.trail.infrastructure.persistence;

import it.unical.geosentinel.trail.domain.HazardReport;
import it.unical.geosentinel.trail.domain.HazardStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;
import java.util.List;

/**
 * Accesso ai dati della tabella hazard_reports.
 */
public interface HazardReportRepository extends JpaRepository<HazardReport, UUID> {
    /**
     * Segnalazioni attive entro una distanza dal tracciato di un sentiero, dalla più recente. Comprende le
     * {@code VERIFIED} di tutti e le {@code SUBMITTED} (non ancora verificate) solo del richiedente, oppure
     * tutte se {@code includeAllSubmitted} è vero, come per il gestore che le deve verificare. Il LIMIT
     * serve perché qualsiasi escursionista può crearne, e il loro numero non ha un tetto.
     *
     * @param trailId             sentiero di riferimento
     * @param bufferMeters        distanza massima dal tracciato, in metri
     * @param requesterId         utente che fa la richiesta
     * @param includeAllSubmitted se includere le segnalazioni in attesa di tutti gli utenti
     * @param limit               numero massimo di risultati
     * @return le segnalazioni trovate
     */
    @Query(value = """
            SELECT h.* FROM hazard_reports h
            INNER JOIN trails t ON t.id = :trailId
            WHERE (h.status = 'VERIFIED'
                   OR (h.status = 'SUBMITTED' AND (:includeAllSubmitted OR h.reporter_id = :requesterId)))
              AND ST_DWithin(h.location::geography, t.path::geography, :bufferMeters)
            ORDER BY h.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<HazardReport> findActiveNearTrail(@Param("trailId") UUID trailId, @Param("bufferMeters") double bufferMeters,
                                           @Param("requesterId") UUID requesterId,
                                           @Param("includeAllSubmitted") boolean includeAllSubmitted,
                                           @Param("limit") int limit);

    /**
     * @param status stato cercato
     * @return tutte le segnalazioni in quello stato, senza paginazione
     */
    List<HazardReport> findByStatus(HazardStatus status);

    /**
     * Versione paginata, usata per la coda delle segnalazioni da verificare del gestore: le
     * {@code SUBMITTED} di tutti, anche lontane dai sentieri.
     *
     * @param status   stato cercato
     * @param pageable paginazione e ordinamento
     * @return la pagina di segnalazioni
     */
    Page<HazardReport> findByStatus(HazardStatus status, Pageable pageable);

    /**
     * Segnalazioni inviate da un utente, per la lista "Le mie segnalazioni" dell'app. La query usa
     * l'indice idx_hazard_reports_reporter.
     *
     * @param reporterId id dell'autore
     * @param pageable   paginazione e ordinamento
     * @return la pagina di segnalazioni
     */
    Page<HazardReport> findByReporterId(UUID reporterId, Pageable pageable);

    /**
     * Pericoli attivi attorno a un punto, per la funzione "Pericoli vicini" dell'app: solo i
     * {@code VERIFIED} di tutti gli utenti (non quelli in verifica, respinti o risolti), dal più vicino.
     * {@code ST_DWithin} sul tipo geography lavora in metri e usa l'indice idx_hazard_reports_location_geog.
     *
     * @param lon          longitudine del centro
     * @param lat          latitudine del centro
     * @param radiusMeters raggio in metri
     * @param limit        numero massimo di risultati
     * @return le segnalazioni trovate
     */
    @Query(value = """
            SELECT h.* FROM hazard_reports h
            WHERE h.status = 'VERIFIED'
              AND ST_DWithin(h.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)
            ORDER BY ST_Distance(h.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<HazardReport> findActiveNearby(@Param("lon") double lon, @Param("lat") double lat, @Param("radiusMeters") double radiusMeters, @Param("limit") int limit);
}
