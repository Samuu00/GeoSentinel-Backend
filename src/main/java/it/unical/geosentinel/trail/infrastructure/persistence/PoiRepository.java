// Repository Spring Data JPA dei punti di interesse, con una query nativa PostGIS per la ricerca attorno
// a un punto.
package it.unical.geosentinel.trail.infrastructure.persistence;

import it.unical.geosentinel.trail.domain.Poi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;
import java.util.List;

/**
 * Accesso ai dati della tabella pois.
 */
public interface PoiRepository extends JpaRepository<Poi, UUID>{
    /**
     * POI entro un raggio da un punto, dal più vicino, con un filtro facoltativo sulla categoria. Il
     * parametro facoltativo si confronta con {@code CAST(:type AS varchar) IS NULL}: senza il cast
     * PostgreSQL non riesce a dedurre il tipo di un parametro nullo.
     *
     * @param lon          longitudine del centro
     * @param lat          latitudine del centro
     * @param radiusMeters raggio in metri
     * @param type         nome della categoria, oppure {@code null} per tutte
     * @param limit        numero massimo di risultati
     * @return i POI trovati
     */
    @Query(value = """
            SELECT p.* FROM pois p
            WHERE ST_DWithin(p.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)
              AND (CAST(:type AS varchar) IS NULL OR p.type = CAST(:type AS varchar))
            ORDER BY ST_Distance(p.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Poi> findNearby(@Param("lon") double lon, @Param("lat") double lat, @Param("radiusMeters") double radiusMeters, @Param("type") String type, @Param("limit") int limit);

    /**
     * @param trailId identificativo del sentiero
     * @return i POI collegati al sentiero
     */
    List<Poi> findByTrailId(UUID trailId);
}
