package it.unical.geosentinel.trail.infrastructure.persistence;

import it.unical.geosentinel.trail.domain.Trail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;
import java.util.List;

public interface TrailRepository extends JpaRepository<Trail, UUID>{
    /**
     * Le aree da non attraversare nel percorso A→B (ADR-0028): i sentieri CLOSED allargati di :bufferMeters, uniti
     * e semplificati, come GeoJSON MultiPolygon in [lon, lat]. Null se non ce ne sono.
     */
    @Query(value = """
            SELECT ST_AsGeoJSON(ST_Multi(ST_SimplifyPreserveTopology(
                       ST_Union(ST_Buffer(t.path::geography, :bufferMeters)::geometry), 0.00002)), 6)
            FROM trails t WHERE t.status = 'CLOSED'
            """, nativeQuery = true)
    String findClosedAreasGeoJson(@Param("bufferMeters") double bufferMeters);

    /**
     * I sentieri aperti che il percorso :routeWkt (LINESTRING in 4326) segue per almeno :minOverlapMeters entro
     * :toleranceMeters, nell'ordine in cui li incontra (il punto medio del tratto in comune lungo il percorso).
     */
    @Query(value = """
            WITH r AS (SELECT ST_GeomFromText(:routeWkt, 4326) AS g)
            SELECT CAST(t.id AS varchar) FROM r, trails t
            CROSS JOIN LATERAL (SELECT ST_Intersection(t.path, ST_Buffer(r.g::geography, :toleranceMeters)::geometry) AS shared) o
            WHERE t.status <> 'CLOSED'
              AND ST_DWithin(t.path::geography, r.g::geography, :toleranceMeters)
              AND ST_Length(o.shared::geography) >= :minOverlapMeters
            ORDER BY ST_LineLocatePoint(r.g, ST_ClosestPoint(r.g, ST_Centroid(o.shared)))
            """, nativeQuery = true)
    List<String> findTrailIdsAlong(@Param("routeWkt") String routeWkt, @Param("toleranceMeters") double toleranceMeters,
                                   @Param("minOverlapMeters") double minOverlapMeters);

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
