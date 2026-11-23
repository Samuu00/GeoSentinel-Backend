/*
 * Porta del limite dei tentativi sui codici della verifica in due passaggi. L'implementazione usa Redis.
 */
package it.unical.geosentinel.identity.application.port;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Limite dei codici di verifica per account, con due contatori separati: uno per il login e uno per le
 * operazioni in sessione (cambio password, disattivazione, codici di recupero). Così chi conosce solo la
 * password esaurisce i tentativi del login, ma non blocca il titolare già autenticato.
 */
public interface MfaAttemptLimiter {
    /**
     * Consuma un tentativo del login.
     *
     * @param userId utente
     * @return vuoto se il tentativo è consentito, altrimenti quanto attendere
     */
    Optional<Duration> tryAcquireLogin(UUID userId);

    /**
     * Consuma un tentativo delle operazioni in sessione.
     *
     * @param userId utente
     * @return vuoto se il tentativo è consentito, altrimenti quanto attendere
     */
    Optional<Duration> tryAcquireInSession(UUID userId);

    /**
     * Azzera entrambi i contatori: dopo un codice giusto, un reset della password o un azzeramento da parte
     * di un amministratore.
     *
     * @param userId utente
     */
    void reset(UUID userId);

    /**
     * Conta un errore del login nell'ultima ora. È un contatore separato dal limite e serve a decidere quando
     * mandare l'email d'avviso.
     *
     * @param userId utente
     * @return il numero di errori dopo l'incremento
     */
    long recordLoginFailure(UUID userId);
}
