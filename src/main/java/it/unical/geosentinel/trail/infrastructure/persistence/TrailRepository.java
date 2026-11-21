package it.unical.geosentinel.trail.infrastructure.persistence;

import it.unical.geosentinel.trail.domain.Trail;
import it.unical.geosentinel.trail.domain.TrailStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;
import java.util.List;

public interface TrailRepository extends JpaRepository<Trail, UUID>{
    // La rete del percorso A→B (RouteService): tutti i sentieri non chiusi, col percorso completo
    List<Trail> findByStatusNot(TrailStatus status);

    @Query(value = """
            SELECT t.* FROM trails t
            WHERE ST_DWithin(t.path::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)
            ORDER BY ST_Distance(t.path::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Trail> findWithinDistance(@Param("lon") double lon, @Param("lat") double lat, @Param("radiusMeters") double radiusMeters, @Param("limit") int limit);

    @Query(value = """
            SELECT t.* FROM trails t
            WHERE ST_Intersects(ST_MakeEnvelope(:minLon, :minLat, :maxLon, :maxLat, 4326), t.path)
            ORDER BY t.id
            LIMIT :limit
            """, nativeQuery = true)
    List<Trail> findIntersecting(@Param("minLon") double minLon, @Param("minLat") double minLat, @Param("maxLon") double maxLon, @Param("maxLat") double maxLat, @Param("limit") int limit);
}
