/*
 * Repository Spring Data JPA della configurazione TOTP degli utenti.
 */
package it.unical.geosentinel.identity.infrastructure.persistence;

import it.unical.geosentinel.identity.domain.UserMfa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Accesso alla tabella {@code user_mfa}, la cui chiave primaria è l'id dell'utente.
 */
public interface UserMfaRepository extends JpaRepository<UserMfa, UUID> {
    /**
     * Cancella la configurazione dell'utente con una DELETE in blocco, senza caricare l'entità (disattivazione,
     * azzeramento da amministratore, cancellazione dell'account).
     *
     * @param userId utente
     * @return il numero di righe cancellate
     */
    @Modifying
    @Query("DELETE FROM UserMfa m WHERE m.userId = :userId")
    int deleteAllOfUser(@Param("userId") UUID userId);
}
