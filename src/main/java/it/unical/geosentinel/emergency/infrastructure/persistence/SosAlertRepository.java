package it.unical.geosentinel.emergency.infrastructure.persistence;

import it.unical.geosentinel.emergency.domain.SosAlert;
import it.unical.geosentinel.emergency.domain.SosStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SosAlertRepository extends JpaRepository<SosAlert, UUID>{
    @Query("""
            SELECT a FROM SosAlert a
            LEFT JOIN FETCH a.assignedTeam
            WHERE a.id = :id
            """)
    Optional<SosAlert> findByIdWithDetails(@Param("id") UUID id);

    // senza fetch della squadra: il service la blocca dopo, rispettando l'ordine alert -> team
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM SosAlert a WHERE a.id = :id")
    Optional<SosAlert> findByIdForUpdate(@Param("id") UUID id);

    // 404 per l'allerta di un altro escursionista: non se ne rivela l'esistenza
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM SosAlert a WHERE a.id = :id AND a.hikerId = :hikerId")
    Optional<SosAlert> findByIdAndHikerIdForUpdate(@Param("id") UUID id, @Param("hikerId") UUID hikerId);

    Optional<SosAlert> findFirstByHikerIdAndStatusIn(UUID hikerId, Collection<SosStatus> statuses);
    Optional<SosAlert> findByHikerIdAndIdempotencyKey(UUID hikerId, UUID idempotencyKey);

    // lock transazionale per escursionista: due SOS simultanei (retry, doppio tap) si mettono in fila
    // e il secondo trova l'allerta del primo. Si rilascia da solo al commit
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:key)", nativeQuery = true)
    Integer lockHiker(@Param("key") long key);

    // allerte attive mai notificate (l'app si è fermata tra il commit e la notifica): le rilancia SosNotificationRecovery
    @Query(value = """
            SELECT a.* FROM sos_alerts a
            WHERE a.status IN ('ACTIVE', 'DISPATCHED')
              AND a.notified_at IS NULL
              AND a.created_at < :cutoff
            ORDER BY a.created_at
            LIMIT 100
            """, nativeQuery = true)
    List<SosAlert> findUnnotifiedCreatedBefore(@Param("cutoff") Instant cutoff);

    // gira fuori dalla transazione del raise (listener AFTER_COMMIT): la transazione è la sua
    @Transactional
    @Modifying
    @Query(value = "UPDATE sos_alerts SET notified_at = :now WHERE id = :id AND notified_at IS NULL", nativeQuery = true)
    int markNotified(@Param("id") UUID id, @Param("now") Instant now);

    // GDPR: posizione arrotondata a 0.01° (~1 km) e note mediche cancellate per le allerte chiuse prima di :cutoff
    @Modifying
    @Query(value = """
            UPDATE sos_alerts
            SET location = ST_SnapToGrid(location, 0.01), medical_notes = NULL, anonymized_at = :now
            WHERE closed_at < :cutoff AND anonymized_at IS NULL
            """, nativeQuery = true)
    int anonymizeClosedBefore(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @EntityGraph(attributePaths = "assignedTeam")
    Page<SosAlert> findByStatusIn(Collection<SosStatus> statuses, Pageable pageable);

    @Query(value = """
            SELECT a.* FROM sos_alerts a
            WHERE a.status IN ('ACTIVE', 'DISPATCHED')
              AND ST_DWithin(a.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)
            ORDER BY ST_Distance(a.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<SosAlert> findNearbyActive(@Param("lon") double lon, @Param("lat") double lat, @Param("radiusMeters") double radiusMeters, @Param("limit") int limit);
}
