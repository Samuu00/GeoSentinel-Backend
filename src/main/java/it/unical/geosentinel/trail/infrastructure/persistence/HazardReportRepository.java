package it.unical.geosentinel.trail.infrastructure.persistence;

import it.unical.geosentinel.trail.domain.HazardReport;
import it.unical.geosentinel.trail.domain.HazardStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;
import java.util.List;

public interface HazardReportRepository extends JpaRepository<HazardReport, UUID> {
    @Query(value = """
            SELECT h.* FROM hazard_reports h
            INNER JOIN trails t ON t.id = :trailId
            WHERE h.status IN ('SUBMITTED', 'VERIFIED')
              AND ST_DWithin(h.location::geography, t.path::geography, :bufferMeters)
            """, nativeQuery = true)
    List<HazardReport> findActiveNearTrail(@Param("trailId") UUID trailId, @Param("bufferMeters") double bufferMeters);

    List<HazardReport> findByStatus(HazardStatus status);
}
