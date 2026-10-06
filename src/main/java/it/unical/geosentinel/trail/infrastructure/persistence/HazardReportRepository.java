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

public interface HazardReportRepository extends JpaRepository<HazardReport, UUID> {
    // VERIFIED di tutti; SUBMITTED (non ancora verificate) solo del richiedente, o tutte se includeAllSubmitted
    // (PARK_MANAGER, che le deve verificare). Dalla più recente, con LIMIT: qualsiasi HIKER può crearne
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

    List<HazardReport> findByStatus(HazardStatus status);

    // Le segnalazioni di un utente (lista "Le mie segnalazioni" dell'app), su idx_hazard_reports_reporter
    Page<HazardReport> findByReporterId(UUID reporterId, Pageable pageable);

    // "Pericoli vicino a me" (M4.4 dell'app): solo VERIFIED (niente in verifica, falsi o risolti), di tutti gli utenti,
    // dal più vicino. ST_DWithin sul geography usa idx_hazard_reports_location_geog (V4)
    @Query(value = """
            SELECT h.* FROM hazard_reports h
            WHERE h.status = 'VERIFIED'
              AND ST_DWithin(h.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)
            ORDER BY ST_Distance(h.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<HazardReport> findActiveNearby(@Param("lon") double lon, @Param("lat") double lat, @Param("radiusMeters") double radiusMeters, @Param("limit") int limit);
}
