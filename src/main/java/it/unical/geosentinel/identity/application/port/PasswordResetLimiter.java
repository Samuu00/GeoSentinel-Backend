/*
 * Porta del limite delle richieste di reset della password per account, che evita di usare il servizio per
 * inondare di email una casella. L'implementazione usa Redis.
 */
package it.unical.geosentinel.identity.application.port;

import java.time.Duration;
import java.util.Optional;

/**
 * Limite delle richieste di reset della password.
 */
public interface PasswordResetLimiter {
    /**
     * Consuma una richiesta.
     *
     * @param accountKey hash dell'email
     * @return vuoto se la richiesta è consentita, altrimenti quanto attendere
     */
    Optional<Duration> tryAcquire(String accountKey);
}
