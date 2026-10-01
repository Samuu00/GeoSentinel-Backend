package it.unical.geosentinel.emergency.infrastructure.persistence;

import it.unical.geosentinel.emergency.domain.RescueTeam;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RescueTeamRepository extends JpaRepository<RescueTeam, UUID>{
    boolean existsByNameIgnoreCase(String name);

    // sempre dopo il lock dell'allerta (ordine fisso alert -> team, niente deadlock)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RescueTeam t WHERE t.id = :id")
    Optional<RescueTeam> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            SELECT t.* FROM rescue_teams t
            WHERE t.status = 'AVAILABLE'
              AND ST_DWithin(t.base_location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)
            ORDER BY ST_Distance(t.base_location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<RescueTeam> findAvailableNearby(@Param("lon") double lon, @Param("lat") double lat, @Param("radiusMeters") double radiusMeters, @Param("limit") int limit);
}
