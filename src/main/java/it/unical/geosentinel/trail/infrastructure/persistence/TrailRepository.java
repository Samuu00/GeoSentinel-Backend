// Repository Spring Data JPA dei sentieri. Le query native PostGIS servono alle ricerche geografiche e al
// calcolo dei percorsi: aree da evitare attorno ai sentieri chiusi e sentieri seguiti da un percorso.
package it.unical.geosentinel.trail.infrastructure.persistence;

import it.unical.geosentinel.trail.domain.Trail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;
import java.util.List;

/**
 * Accesso ai dati della tabella trails.
 */
public interface TrailRepository extends JpaRepository<Trail, UUID>{
    /**
     * Aree da non attraversare nel calcolo dei percorsi: i sentieri {@code CLOSED} allargati di
     * {@code bufferMeters}, uniti in una sola geometria e semplificati, in formato GeoJSON MultiPolygon con
     * coordinate {@code [lon, lat]} e 6 decimali.
     *
     * @param bufferMeters raggio dell'area attorno a ogni sentiero chiuso, in metri
     * @return il GeoJSON, oppure {@code null} se non ci sono sentieri chiusi
     */
    @Query(value = """
            SELECT ST_AsGeoJSON(ST_Multi(ST_SimplifyPreserveTopology(
                       ST_Union(ST_Buffer(t.path::geography, :bufferMeters)::geometry), 0.00002)), 6)
            FROM trails t WHERE t.status = 'CLOSED'
            """, nativeQuery = true)
    String findClosedAreasGeoJson(@Param("bufferMeters") double bufferMeters);

    /**
     * Sentieri non chiusi che il percorso segue per almeno {@code minOverlapMeters} restando entro
     * {@code toleranceMeters}. Sono ordinati nell'ordine in cui il percorso li incontra, cioè secondo la
     * posizione lungo il percorso del centro del tratto in comune.
     *
     * @param routeWkt         percorso come LINESTRING WKT in SRID 4326
     * @param toleranceMeters  distanza massima tra percorso e sentiero, in metri
     * @param minOverlapMeters lunghezza minima del tratto in comune, in metri
     * @return gli id dei sentieri come stringhe
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

    /**
     * Sentieri entro un raggio da un punto, dal più vicino.
     *
     * @param lon          longitudine del centro
     * @param lat          latitudine del centro
     * @param radiusMeters raggio in metri
     * @param limit        numero massimo di risultati
     * @return i sentieri trovati
     */
    @Query(value = """
            SELECT t.* FROM trails t
            WHERE ST_DWithin(t.path::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)
            ORDER BY ST_Distance(t.path::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Trail> findWithinDistance(@Param("lon") double lon, @Param("lat") double lat, @Param("radiusMeters") double radiusMeters, @Param("limit") int limit);

    /**
     * Sentieri che attraversano un rettangolo, ordinati per id per avere risultati stabili. Si usa
     * {@code ST_Intersects} sulla geometria, che sfrutta l'indice spaziale del tracciato.
     *
     * @param minLon longitudine del lato ovest
     * @param minLat latitudine del lato sud
     * @param maxLon longitudine del lato est
     * @param maxLat latitudine del lato nord
     * @param limit  numero massimo di risultati
     * @return i sentieri trovati
     */
    @Query(value = """
            SELECT t.* FROM trails t
            WHERE ST_Intersects(ST_MakeEnvelope(:minLon, :minLat, :maxLon, :maxLat, 4326), t.path)
            ORDER BY t.id
            LIMIT :limit
            """, nativeQuery = true)
    List<Trail> findIntersecting(@Param("minLon") double minLon, @Param("minLat") double minLat, @Param("maxLon") double maxLon, @Param("maxLat") double maxLat, @Param("limit") int limit);
}
