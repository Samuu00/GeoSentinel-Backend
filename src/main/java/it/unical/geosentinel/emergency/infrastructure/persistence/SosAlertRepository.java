// Repository Spring Data JPA delle allerte SOS: lock pessimistici e advisory lock per la concorrenza,
// query per le notifiche da recuperare, per l'anonimizzazione dei dati e per la ricerca geografica.
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

/**
 * Accesso alla tabella sos_alerts. Lo usano {@code SosService}, il listener e il job di recupero delle
 * notifiche e il job di conservazione dei dati. Alcune colonne (notified_at, anonymized_at) non sono mappate
 * nell'entità e vengono scritte solo dalle query native di questa interfaccia.
 */
public interface SosAlertRepository extends JpaRepository<SosAlert, UUID>{
    /**
     * Legge l'allerta insieme alla squadra assegnata, con un solo JOIN.
     *
     * @param id id dell'allerta
     * @return l'allerta, se esiste
     */
    @Query("""
            SELECT a FROM SosAlert a
            LEFT JOIN FETCH a.assignedTeam
            WHERE a.id = :id
            """)
    Optional<SosAlert> findByIdWithDetails(@Param("id") UUID id);

    /**
     * Legge l'allerta con un lock pessimistico in scrittura. La squadra non viene caricata insieme: il servizio
     * la blocca dopo, rispettando l'ordine fisso allerta, poi squadra.
     *
     * @param id id dell'allerta
     * @return l'allerta, se esiste
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM SosAlert a WHERE a.id = :id")
    Optional<SosAlert> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Come {@link #findByIdForUpdate}, ma restituisce l'allerta solo se appartiene all'escursionista indicato.
     * L'allerta di un altro utente risulta quindi inesistente (404), senza rivelare che esiste.
     *
     * @param id id dell'allerta
     * @param hikerId id dell'escursionista autenticato
     * @return l'allerta, se esiste ed è sua
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM SosAlert a WHERE a.id = :id AND a.hikerId = :hikerId")
    Optional<SosAlert> findByIdAndHikerIdForUpdate(@Param("id") UUID id, @Param("hikerId") UUID hikerId);

    /**
     * @return la prima allerta dell'escursionista in uno degli stati indicati (di solito quelli aperti)
     */
    Optional<SosAlert> findFirstByHikerIdAndStatusIn(UUID hikerId, Collection<SosStatus> statuses);
    /**
     * @return l'allerta creata dall'escursionista con quella chiave di idempotenza, per riconoscere i retry
     */
    Optional<SosAlert> findByHikerIdAndIdempotencyKey(UUID hikerId, UUID idempotencyKey);
    /**
     * Conta le allerte create dall'escursionista dopo un certo istante; serve al limite delle push agli
     * operatori.
     *
     * @return il numero di allerte create dopo since
     */
    long countByHikerIdAndCreatedAtAfter(UUID hikerId, Instant since);

    /**
     * Prende un advisory lock transazionale di PostgreSQL legato all'escursionista. Due SOS simultanei dello
     * stesso utente (un retry, un doppio tocco) si mettono in fila e il secondo trova l'allerta creata dal
     * primo. Il lock si rilascia da solo alla fine della transazione.
     *
     * @param key chiave numerica a 64 bit ricavata dall'id dell'escursionista
     * @return sempre 1; il valore non viene usato
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:key)", nativeQuery = true)
    Integer lockHiker(@Param("key") long key);

    /**
     * Allerte aperte mai notificate agli operatori e create prima di cutoff, al massimo 100 per volta e dalla
     * più vecchia. Succede quando il backend si ferma tra il commit e l'invio della push; le rinotifica
     * {@code SosNotificationRecovery}.
     *
     * @param cutoff istante prima del quale l'allerta deve essere stata creata
     * @return le allerte da rinotificare
     */
    @Query(value = """
            SELECT a.* FROM sos_alerts a
            WHERE a.status IN ('ACTIVE', 'DISPATCHED')
              AND a.notified_at IS NULL
              AND a.created_at < :cutoff
            ORDER BY a.created_at
            LIMIT 100
            """, nativeQuery = true)
    List<SosAlert> findUnnotifiedCreatedBefore(@Param("cutoff") Instant cutoff);

    /**
     * Segna l'allerta come notificata, solo se non lo era già. Ha una transazione propria perché viene
     * chiamato dal listener dopo il commit, quando la transazione che ha creato l'allerta è già chiusa.
     *
     * @param id id dell'allerta
     * @param now istante della notifica
     * @return il numero di righe aggiornate (0 se era già notificata)
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE sos_alerts SET notified_at = :now WHERE id = :id AND notified_at IS NULL", nativeQuery = true)
    int markNotified(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Anonimizza le allerte chiuse prima di cutoff e non ancora anonimizzate (GDPR): la posizione viene
     * arrotondata a una griglia di 0,01 gradi (circa 1 km) e le note mediche vengono cancellate. La
     * transazione la apre il chiamante, {@code SosDataRetentionJob}.
     *
     * @param cutoff limite di chiusura delle allerte da trattare
     * @param now istante da salvare in anonymized_at
     * @return il numero di allerte anonimizzate
     */
    @Modifying
    @Query(value = """
            UPDATE sos_alerts
            SET location = ST_SnapToGrid(location, 0.01), medical_notes = NULL, anonymized_at = :now
            WHERE closed_at < :cutoff AND anonymized_at IS NULL
            """, nativeQuery = true)
    int anonymizeClosedBefore(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    /**
     * Pagina di allerte negli stati indicati, con la squadra caricata insieme (niente query N+1).
     */
    @EntityGraph(attributePaths = "assignedTeam")
    Page<SosAlert> findByStatusIn(Collection<SosStatus> statuses, Pageable pageable);

    /**
     * Allerte aperte entro radiusMeters dal punto indicato, ordinate dalla più vicina, con distanze geodetiche
     * calcolate da PostGIS sul tipo geography.
     *
     * @param lon longitudine del centro
     * @param lat latitudine del centro
     * @param radiusMeters raggio in metri
     * @param limit numero massimo di righe
     * @return le allerte trovate
     */
    @Query(value = """
            SELECT a.* FROM sos_alerts a
            WHERE a.status IN ('ACTIVE', 'DISPATCHED')
              AND ST_DWithin(a.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radiusMeters)
            ORDER BY ST_Distance(a.location::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<SosAlert> findNearbyActive(@Param("lon") double lon, @Param("lat") double lat, @Param("radiusMeters") double radiusMeters, @Param("limit") int limit);
}
