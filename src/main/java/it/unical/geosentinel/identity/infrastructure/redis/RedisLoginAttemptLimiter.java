/*
 * Implementazione su Redis del limite dei tentativi di login per account (porta LoginAttemptLimiter), a due
 * livelli: uno stretto per account e IP, uno largo per account su tutti gli IP.
 */
package it.unical.geosentinel.identity.infrastructure.redis;

import it.unical.geosentinel.identity.application.port.LoginAttemptLimiter;
import it.unical.geosentinel.identity.infrastructure.security.ClientIp;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Token bucket per account a due livelli.
 * <p>
 * Con un solo secchio per account, chiunque potrebbe tenere bloccato il login di una vittima sbagliando la sua
 * password di continuo. Qui il limite stretto vale per la coppia account e IP ({@code rl:login:<hash>:<ip>}):
 * chi sbaglia da un IP blocca soltanto quell'IP, mentre il titolare dal proprio IP entra ancora. Il credential
 * stuffing distribuito su molti IP resta fermato dal secondo secchio, per account su tutti gli IP
 * ({@code rl:login:<hash>}), più capiente ma comunque troppo lento per provare molte password.
 * <p>
 * Entrambi sono fail-closed come il resto degli endpoint sotto {@code /auth}: con Redis spento il login risponde
 * 429 invece di restare senza protezione contro i tentativi a forza bruta. È una scelta voluta.
 */
@Component
public class RedisLoginAttemptLimiter implements LoginAttemptLimiter {
    private static final String KEY_PREFIX = "rl:login:";
    /** Valore usato al posto dell'IP quando il codice non sta servendo una richiesta HTTP. */
    private static final String NO_CLIENT = "none";
    /** 10 tentativi ogni 15 minuti per account e IP (un gettone ricaricato ogni 90 secondi). */
    private static final RateLimitPolicy PER_SOURCE = new RateLimitPolicy(10, 10.0 / 900, true);
    /** 100 tentativi all'ora per account, sommando tutti gli IP (un gettone ogni 36 secondi). */
    private static final RateLimitPolicy PER_ACCOUNT = new RateLimitPolicy(100, 100.0 / 3600, true);

    private final RedisRateLimiter redisRateLimiter;

    public RedisLoginAttemptLimiter(RedisRateLimiter redisRateLimiter) {
        this.redisRateLimiter = redisRateLimiter;
    }

    /**
     * Consuma un tentativo dal secchio dell'account e dell'IP corrente e, se c'era, da quello dell'account. Se il
     * primo rifiuta, il secondo non viene toccato: chi è già bloccato non consuma i tentativi degli altri IP.
     *
     * @param accountKey hash dell'email o del subject Google
     * @return vuoto se il tentativo è consentito, altrimenti quanto attendere prima di riprovare
     */
    @Override
    public Optional<Duration> tryAcquire(String accountKey) {
        RateLimitDecision perSource = redisRateLimiter.tryConsume(sourceKey(accountKey), PER_SOURCE);
        if (!perSource.allowed()) {
            return Optional.of(Duration.ofMillis(perSource.retryAfterMillis()));
        }
        RateLimitDecision perAccount = redisRateLimiter.tryConsume(KEY_PREFIX + accountKey, PER_ACCOUNT);
        return perAccount.allowed() ? Optional.empty() : Optional.of(Duration.ofMillis(perAccount.retryAfterMillis()));
    }

    /**
     * Svuota i contatori dell'account: quello dell'IP corrente e quello su tutti gli IP. Si chiama dopo un login
     * riuscito o un reset della password.
     *
     * @param accountKey hash dell'email o del subject Google
     */
    @Override
    public void reset(String accountKey) {
        redisRateLimiter.reset(sourceKey(accountKey));
        redisRateLimiter.reset(KEY_PREFIX + accountKey);
    }

    /**
     * Chiave del secchio stretto, per account e IP del client che sta facendo la richiesta.
     *
     * @param accountKey hash dell'account
     * @return la chiave Redis
     */
    private static String sourceKey(String accountKey) {
        return KEY_PREFIX + accountKey + ":" + ClientIp.current().orElse(NO_CLIENT);
    }
}
