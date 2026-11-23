// Repository Spring Data JPA dei rifugi, con la lettura sotto lock pessimistico usata dalle prenotazioni
// e la ricerca geografica attorno a un punto.
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

/**
 * Accesso ai dati della tabella refuges.
 */
public interface RefugeRepository extends JpaRepository<Refuge, UUID>{
    /**
     * Legge il rifugio con un lock pessimistico in scrittura ({@code SELECT ... FOR UPDATE}). Le
     * prenotazioni concorrenti sullo stesso rifugio vengono così eseguite una alla volta e il controllo
     * dei posti non può essere superato da due richieste insieme. Se il lock non arriva entro il
     * lock_timeout di PostgreSQL la richiesta fallisce con un 409 resource-busy.
     *
     * @param id identificativo del rifugio
     * @return il rifugio bloccato, se esiste
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Refuge r WHERE r.id = :id")
    Optional<Refuge> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Rifugi entro un raggio da un punto, dal più vicino.
     *
     * @param lon          longitudine del centro
     * @param lat          latitudine del centro
     * @param radiusMeters raggio in metri
     * @param limit        numero massimo di risultati
     * @return i rifugi trovati
     */
    @Query(value = """
            SELECT r.* FROM refuges r
            WHERE ST_DWithin(r.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)
            ORDER BY ST_Distance(r.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Refuge> findNearby(@Param("lon") double lon, @Param("lat") double lat, @Param("radiusMeters") double radiusMeters, @Param("limit") int limit);
    /**
     * @param managerId id del gestore
     * @return i rifugi gestiti dall'utente
     */
    List<Refuge> findByManagerId(UUID managerId);
}
