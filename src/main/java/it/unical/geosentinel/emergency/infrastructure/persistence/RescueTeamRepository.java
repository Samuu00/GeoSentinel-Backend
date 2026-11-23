// Repository Spring Data JPA delle squadre di soccorso: oltre alle operazioni standard offre il lock
// pessimistico usato durante l'invio e la ricerca geografica delle squadre libere.
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

/**
 * Accesso alla tabella rescue_teams. Lo usa {@code SosService}.
 */
public interface RescueTeamRepository extends JpaRepository<RescueTeam, UUID>{
    /**
     * @param name nome da cercare, senza distinguere maiuscole e minuscole
     * @return true se esiste già una squadra con quel nome
     */
    boolean existsByNameIgnoreCase(String name);

    /**
     * Legge la squadra con un lock pessimistico in scrittura (SELECT ... FOR UPDATE). Va chiamato sempre dopo
     * aver bloccato l'allerta: l'ordine fisso allerta, poi squadra evita i deadlock.
     *
     * @param id id della squadra
     * @return la squadra, se esiste
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RescueTeam t WHERE t.id = :id")
    Optional<RescueTeam> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Squadre AVAILABLE la cui base dista al massimo radiusMeters dal punto indicato, ordinate dalla più
     * vicina. Il cast a geography fa calcolare a PostGIS distanze geodetiche in metri invece che in gradi.
     *
     * @param lon longitudine del centro
     * @param lat latitudine del centro
     * @param radiusMeters raggio in metri
     * @param limit numero massimo di righe
     * @return le squadre trovate
     */
    @Query(value = """
            SELECT t.* FROM rescue_teams t
            WHERE t.status = 'AVAILABLE'
              AND ST_DWithin(t.base_location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)
            ORDER BY ST_Distance(t.base_location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<RescueTeam> findAvailableNearby(@Param("lon") double lon, @Param("lat") double lat, @Param("radiusMeters") double radiusMeters, @Param("limit") int limit);
}
