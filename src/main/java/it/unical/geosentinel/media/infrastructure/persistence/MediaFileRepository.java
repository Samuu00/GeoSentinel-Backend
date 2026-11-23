// Repository Spring Data JPA dei metadati dei file. Oltre alle ricerche per proprietario ed entità
// contiene il calcolo della quota usata, il lock applicativo di PostgreSQL che serializza i caricamenti
// dello stesso utente e la cancellazione condizionale usata dal job di pulizia.
package it.unical.geosentinel.media.infrastructure.persistence;

import it.unical.geosentinel.media.domain.MediaFile;
import it.unical.geosentinel.media.domain.MediaStatus;
import it.unical.geosentinel.media.domain.MediaTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accesso ai dati della tabella media_files.
 */
public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {
    /**
     * @param id      identificativo del file
     * @param ownerId proprietario
     * @return il file, se esiste ed è dell'utente
     */
    Optional<MediaFile> findByIdAndOwnerId(UUID id, UUID ownerId);
    /**
     * @param entityType tipo dell'entità
     * @param entityId   id dell'entità
     * @param status     stato cercato
     * @return i file allegati all'entità in quello stato
     */
    List<MediaFile> findByEntityTypeAndEntityIdAndStatus(MediaTargetType entityType, UUID entityId, MediaStatus status);
    /**
     * @param ownerId  proprietario
     * @param pageable paginazione e ordinamento
     * @return la pagina dei file dell'utente
     */
    Page<MediaFile> findByOwnerId(UUID ownerId, Pageable pageable);

    /**
     * Spazio occupato da un utente ai fini della quota: i file {@code READY} più quelli in attesa che si
     * possono ancora caricare (autorizzazione non scaduta). Senza questi ultimi, tanti caricamenti avviati
     * in parallelo e non ancora confermati aggirerebbero la quota.
     *
     * @param ownerId      proprietario
     * @param pendingSince istante dopo il quale un file in attesa conta ancora
     * @param ready        lo stato READY
     * @param pending      lo stato PENDING_UPLOAD
     * @return i byte occupati
     */
    @Query("""
            SELECT COALESCE(SUM(m.sizeBytes), 0) FROM MediaFile m
            WHERE m.ownerId = :ownerId
              AND (m.status = :ready OR (m.status = :pending AND m.createdAt > :pendingSince))
            """)
    long sumQuotaBytes(@Param("ownerId") UUID ownerId,
                       @Param("pendingSince") Instant pendingSince,
                       @Param("ready") MediaStatus ready,
                       @Param("pending") MediaStatus pending);

    /**
     * Prende un advisory lock transazionale di PostgreSQL che serializza le richieste di caricamento dello
     * stesso utente: senza, due richieste leggerebbero la stessa quota e potrebbero superarla insieme. Si
     * usa la forma a due chiavi intere (namespace e utente), che non collide con il lock a una chiave
     * bigint usato dagli SOS. Il lock si rilascia da solo al commit o al rollback.
     *
     * @param namespace chiave che identifica il tipo di lock
     * @param key       chiave ricavata dall'utente
     * @return sempre 1
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, :key)", nativeQuery = true)
    Integer lockOwner(@Param("namespace") int namespace, @Param("key") int key);

    /**
     * Caricamenti mai confermati e ormai scaduti, che MediaCleanupJob cancella.
     *
     * @param status   lo stato PENDING_UPLOAD
     * @param cutoff   istante prima del quale il file è scaduto
     * @param pageable dimensione del blocco da trattare
     * @return i file scaduti
     */
    List<MediaFile> findByStatusAndCreatedAtBefore(MediaStatus status, Instant cutoff, Pageable pageable);

    /**
     * Cancella la riga solo se è ancora in attesa: se nel frattempo è arrivata una conferma, quella vince
     * e la riga resta.
     *
     * @param id      identificativo del file
     * @param pending lo stato PENDING_UPLOAD
     * @return 1 se la riga è stata cancellata, 0 altrimenti
     */
    @Modifying
    @Query("DELETE FROM MediaFile m WHERE m.id = :id AND m.status = :pending")
    int deleteIfPending(@Param("id") UUID id, @Param("pending") MediaStatus pending);
}
