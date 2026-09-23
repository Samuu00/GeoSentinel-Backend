package it.unical.geosentinel.trail.infrastructure.persistence;

import it.unical.geosentinel.trail.domain.Poi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;
import java.util.List;

public interface PoiRepository extends JpaRepository<Poi, UUID>{
    @Query(value = """
            SELECT p.* FROM pois p
            WHERE ST_DWithin(p.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)
              AND (CAST(:type AS varchar) IS NULL OR p.type = CAST(:type AS varchar))
            ORDER BY ST_Distance(p.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Poi> findNearby(@Param("lon") double lon, @Param("lat") double lat, @Param("radiusMeters") double radiusMeters, @Param("type") String type, @Param("limit") int limit);

    List<Poi> findByTrailId(UUID trailId);
}
