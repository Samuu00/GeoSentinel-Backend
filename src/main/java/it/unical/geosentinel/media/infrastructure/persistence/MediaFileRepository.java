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

public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {
    Optional<MediaFile> findByIdAndOwnerId(UUID id, UUID ownerId);
    List<MediaFile> findByEntityTypeAndEntityIdAndStatus(MediaTargetType entityType, UUID entityId, MediaStatus status);
    Page<MediaFile> findByOwnerId(UUID ownerId, Pageable pageable);

    // Quota: i READY più i PENDING ancora caricabili (URL non scaduto), altrimenti upload paralleli non confermati la aggirano
    @Query("""
            SELECT COALESCE(SUM(m.sizeBytes), 0) FROM MediaFile m
            WHERE m.ownerId = :ownerId
              AND (m.status = :ready OR (m.status = :pending AND m.createdAt > :pendingSince))
            """)
    long sumQuotaBytes(@Param("ownerId") UUID ownerId,
                       @Param("pendingSince") Instant pendingSince,
                       @Param("ready") MediaStatus ready,
                       @Param("pending") MediaStatus pending);

    // Serializza le richieste di upload dello stesso utente: senza, due richieste leggono la stessa quota e la superano.
    // Forma a due chiavi int (namespace + utente): non collide con il lock a una chiave bigint degli SOS. Si rilascia al commit
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, :key)", nativeQuery = true)
    Integer lockOwner(@Param("namespace") int namespace, @Param("key") int key);

    // Upload mai confermati e ormai scaduti: li cancella MediaCleanupJob
    List<MediaFile> findByStatusAndCreatedAtBefore(MediaStatus status, Instant cutoff, Pageable pageable);

    // Condizionale sullo stato: un confirm arrivato nel frattempo vince e la riga resta
    @Modifying
    @Query("DELETE FROM MediaFile m WHERE m.id = :id AND m.status = :pending")
    int deleteIfPending(@Param("id") UUID id, @Param("pending") MediaStatus pending);
}
