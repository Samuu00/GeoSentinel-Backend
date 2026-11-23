/*
 * Porta del limite dei tentativi di login per account, usato contro il credential stuffing (molti IP che
 * provano la stessa email). L'implementazione usa un token bucket su Redis.
 */
package it.unical.geosentinel.identity.application.port;

import java.time.Duration;
import java.util.Optional;

/**
 * Limite dei tentativi di login per account. La chiave è l'hash dell'email (o del subject Google), mai il
 * valore in chiaro.
 */
public interface LoginAttemptLimiter {
    /**
     * Consuma un tentativo.
     *
     * @param accountKey chiave dell'account
     * @return vuoto se il tentativo è consentito, altrimenti quanto attendere prima di riprovare
     */
    Optional<Duration> tryAcquire(String accountKey);

    /**
     * Riporta il limite al massimo, per esempio dopo un reset della password riuscito.
     *
     * @param accountKey chiave dell'account
     */
    void reset(String accountKey);
}
