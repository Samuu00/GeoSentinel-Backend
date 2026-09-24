package it.unical.geosentinel.refuge.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import it.unical.geosentinel.refuge.domain.Refuge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefugeRepository extends JpaRepository<Refuge, UUID>{
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Refuge r WHERE r.id = :id")
    Optional<Refuge> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            SELECT r.* FROM refuges r
            WHERE ST_DWithin(r.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)
            ORDER BY ST_Distance(r.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Refuge> findNearby(@Param("lon") double lon, @Param("lat") double lat, @Param("radiusMeters") double radiusMeters, @Param("limit") int limit);
    List<Refuge> findByManagerId(UUID managerId);
}
