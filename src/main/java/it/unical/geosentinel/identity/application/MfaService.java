/*
 * Servizio applicativo della verifica in due passaggi (TOTP più codici di recupero). Gestisce configurazione,
 * attivazione e disattivazione, la verifica al login e nelle operazioni sensibili, e l'azzeramento da parte di
 * un amministratore. I tentativi sono limitati da MfaAttemptLimiter e le email partono tramite eventi.
 */
package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.identity.application.port.MfaAttemptLimiter;
import it.unical.geosentinel.identity.domain.MfaRecoveryCode;
import it.unical.geosentinel.identity.domain.MfaSecretCipher;
import it.unical.geosentinel.identity.domain.PasswordResetCode;
import it.unical.geosentinel.identity.domain.Totp;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.identity.domain.UserMfa;
import it.unical.geosentinel.identity.infrastructure.persistence.MfaRecoveryCodeRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserMfaRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserRepository;
import it.unical.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import it.unical.geosentinel.shared.domain.exception.InvalidMfaCodeException;
import it.unical.geosentinel.shared.domain.exception.MfaAlreadyEnabledException;
import it.unical.geosentinel.shared.domain.exception.MfaRequiredException;
import it.unical.geosentinel.shared.domain.exception.RateLimitExceededException;
import it.unical.geosentinel.shared.domain.exception.ResourceNotFoundException;
import it.unical.geosentinel.shared.domain.exception.InvalidLoginMfaCodeException;
import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import it.unical.geosentinel.identity.infrastructure.persistence.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Logica della verifica in due passaggi. La usano {@code MfaController} e {@code AdminMfaController} per la
 * gestione, e {@link AuthService} per chiedere il secondo fattore al login, al login con Google e al cambio
 * password. Ogni operazione prende prima il lock sulla riga dell'utente, così due richieste concorrenti sullo
 * stesso account non si sovrappongono.
 */
@Service
public class MfaService {
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Formato di un codice TOTP: esattamente 6 cifre. Tutto il resto viene trattato come codice di recupero. */
    private static final Pattern TOTP_FORMAT = Pattern.compile("\\d{6}");
    /** Lunghezza del segreto TOTP in byte (160 bit, la lunghezza dell'output di HMAC-SHA1). */
    private static final int SECRET_BYTES = 20;
    /** Numero di codici di recupero generati a ogni emissione. */
    private static final int RECOVERY_CODES = 10;
    /** Al terzo codice sbagliato al login nella finestra del limitatore parte l'email d'avviso al titolare. */
    private static final long LOGIN_ALERT_THRESHOLD = 3;
    /** Validità del codice inviato via email durante la configurazione. */
    private static final Duration SETUP_EMAIL_CODE_TTL = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private final UserRepository userRepository;
    private final UserMfaRepository userMfaRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final MfaSecretCipher cipher;
    private final MfaAttemptLimiter attemptLimiter;
    private final TokenBlacklist tokenBlacklist;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public MfaService(UserRepository userRepository,
                      UserMfaRepository userMfaRepository,
                      MfaRecoveryCodeRepository recoveryCodeRepository,
                      RefreshTokenRepository refreshTokenRepository,
                      MfaSecretCipher cipher,
                      MfaAttemptLimiter attemptLimiter,
                      TokenBlacklist tokenBlacklist,
                      ApplicationEventPublisher eventPublisher,
                      Clock clock) {
        this.userRepository = userRepository;
        this.userMfaRepository = userMfaRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.cipher = cipher;
        this.attemptLimiter = attemptLimiter;
        this.tokenBlacklist = tokenBlacklist;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Avvia (o riavvia) la configurazione: genera un segreto nuovo, lo salva cifrato e manda all'email
     * dell'utente un codice di conferma. Il codice email serve perché la registrazione non verifica l'email:
     * dimostra che chi attiva la verifica in due passaggi è il titolare della casella, così chi ha registrato
     * l'email di un altro non può chiudere fuori la vittima quando questa entra con Google.
     *
     * @param userId utente autenticato
     * @return il segreto in Base32 e l'URI otpauth da mostrare all'utente
     * @throws MfaAlreadyEnabledException se la verifica in due passaggi è già attiva
     * @throws ResourceNotFoundException se l'utente non esiste
     */
    @Transactional
    public MfaSetupView startSetup(UUID userId) {
        User user = lockUser(userId);
        UserMfa mfa = userMfaRepository.findById(userId).orElse(null);
        if (mfa != null && mfa.isEnabled()) {
            throw new MfaAlreadyEnabledException();
        }
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        byte[] encrypted = cipher.encrypt(secret);
        Instant now = clock.instant();
        if (mfa == null) {
            mfa = userMfaRepository.save(new UserMfa(userId, encrypted, now));
        } else {
            mfa.replaceSecret(encrypted);
        }
        String emailCode = PasswordResetCode.generate(RANDOM);
        mfa.issueEmailCode(sha256Hex(emailCode), now.plus(SETUP_EMAIL_CODE_TTL));
        eventPublisher.publishEvent(new MfaSetupCodeRequested(user.getEmail(), PasswordResetCode.display(emailCode), SETUP_EMAIL_CODE_TTL));
        return new MfaSetupView(Totp.encodeSecret(secret), Totp.otpauthUri(user.getEmail(), secret));
    }

    /**
     * Conferma la configurazione con il codice dell'app e quello ricevuto via email, attiva la verifica in due
     * passaggi e genera i codici di recupero.
     *
     * @param userId utente autenticato
     * @param code codice TOTP di 6 cifre dall'app di autenticazione
     * @param emailCode codice ricevuto via email
     * @return i codici di recupero in chiaro, da mostrare una sola volta
     * @throws MfaAlreadyEnabledException se la verifica in due passaggi è già attiva
     * @throws BusinessRuleViolationException se la configurazione non è stata avviata
     * @throws InvalidMfaCodeException se uno dei due codici è sbagliato
     * @throws RateLimitExceededException se i tentativi sono esauriti
     */
    @Transactional
    public List<String> enable(UUID userId, String code, String emailCode) {
        User user = lockUser(userId);
        UserMfa mfa = findSetup(userId);
        if (mfa.isEnabled()) {
            throw new MfaAlreadyEnabledException();
        }
        acquireInSessionAttempt(userId);
        Instant now = clock.instant();
        String normalizedEmailCode = emailCode == null ? "" : PasswordResetCode.normalize(emailCode);
        if (!PasswordResetCode.isWellFormed(normalizedEmailCode) || !mfa.emailCodeMatches(sha256Hex(normalizedEmailCode), now)) {
            throw new InvalidMfaCodeException();
        }
        // Il passo si segna come usato: il codice di conferma non può valere anche per un login subito dopo
        mfa.useStep(checkTotp(mfa, requireTotpFormat(code), now));
        attemptLimiter.reset(userId);
        mfa.enable(now);
        eventPublisher.publishEvent(new MfaStatusChanged(user.getEmail(), true));
        return issueRecoveryCodes(userId, now);
    }

    /**
     * Disattiva la verifica in due passaggi dopo aver controllato un codice TOTP o di recupero, e cancella
     * segreto e codici di recupero.
     *
     * @param userId utente autenticato
     * @param code codice TOTP o di recupero
     * @throws BusinessRuleViolationException se la verifica in due passaggi non è attiva
     * @throws InvalidMfaCodeException se il codice è sbagliato
     * @throws RateLimitExceededException se i tentativi sono esauriti
     */
    @Transactional
    public void disable(UUID userId, String code) {
        User user = lockUser(userId);
        UserMfa mfa = findEnabled(userId);
        acquireInSessionAttempt(userId);
        verifyTotpOrRecovery(mfa, userId, code, clock.instant());
        attemptLimiter.reset(userId);
        userMfaRepository.deleteAllOfUser(userId);
        recoveryCodeRepository.deleteAllOfUser(userId);
        eventPublisher.publishEvent(new MfaStatusChanged(user.getEmail(), false));
    }

    /**
     * Sostituisce i codici di recupero con un set nuovo. Richiede un codice TOTP (non un codice di recupero).
     *
     * @param userId utente autenticato
     * @param code codice TOTP di 6 cifre
     * @return i nuovi codici di recupero in chiaro
     * @throws BusinessRuleViolationException se la verifica in due passaggi non è attiva
     * @throws InvalidMfaCodeException se il codice è sbagliato
     * @throws RateLimitExceededException se i tentativi sono esauriti
     */
    @Transactional
    public List<String> regenerateRecoveryCodes(UUID userId, String code) {
        lockUser(userId);
        UserMfa mfa = findEnabled(userId);
        acquireInSessionAttempt(userId);
        Instant now = clock.instant();
        mfa.useStep(checkTotp(mfa, requireTotpFormat(code), now));
        attemptLimiter.reset(userId);
        return issueRecoveryCodes(userId, now);
    }

    /**
     * Chiede il secondo fattore per un'operazione fatta da un utente già autenticato che apre una sessione nuova
     * (il cambio password), come farebbe il login. Se l'utente non ha la verifica in due passaggi non fa nulla.
     * Un codice assente o sbagliato produce un 422 e non un 401, perché un 401 farebbe credere all'app che il
     * token sia scaduto. I tentativi usano il limite delle operazioni in sessione.
     *
     * @param userId utente autenticato
     * @param code codice TOTP o di recupero, può essere {@code null}
     * @throws InvalidMfaCodeException se il codice manca o è sbagliato
     * @throws RateLimitExceededException se i tentativi sono esauriti
     */
    @Transactional
    public void verifyStepUp(UUID userId, String code) {
        lockUser(userId);
        UserMfa mfa = userMfaRepository.findById(userId).filter(UserMfa::isEnabled).orElse(null);
        if (mfa == null) {
            return;
        }
        if (code == null || code.isBlank()) {
            throw new InvalidMfaCodeException();
        }
        acquireInSessionAttempt(userId);
        verifyTotpOrRecovery(mfa, userId, code, clock.instant());
        attemptLimiter.reset(userId);
    }


    /**
     * Verifica il secondo fattore durante il login, dopo che la password (o Google) è già stata controllata.
     * Se l'utente non ha la verifica in due passaggi non fa nulla. Al terzo errore nella finestra del
     * limitatore pubblica l'evento che manda l'email d'avviso al titolare. Con {@code noRollbackFor} l'errore
     * resta committato insieme al contatore e all'evento, come in {@link AuthService#login}.
     *
     * @param user utente che sta facendo login
     * @param mfaCode codice TOTP o di recupero, può essere {@code null}
     * @throws MfaRequiredException se il codice manca (l'app deve chiederlo)
     * @throws InvalidLoginMfaCodeException se il codice è sbagliato
     * @throws RateLimitExceededException se i tentativi sono esauriti
     */
    @Transactional(noRollbackFor = InvalidMfaCodeException.class)
    public void verifyLogin(User user, String mfaCode) {
        UUID userId = user.getId();
        lockUser(userId);
        UserMfa mfa = userMfaRepository.findById(userId).filter(UserMfa::isEnabled).orElse(null);
        if (mfa == null) {
            return;
        }
        if (mfaCode == null || mfaCode.isBlank()) {
            throw new MfaRequiredException();
        }
        attemptLimiter.tryAcquireLogin(userId).ifPresent(retryAfter -> {
            throw new RateLimitExceededException(retryAfter);
        });
        try {
            verifyTotpOrRecovery(mfa, userId, mfaCode, clock.instant());
        } catch (InvalidMfaCodeException e) {
            // L'avviso contiene solo il tipo di evento e l'email: niente IP né orario
            if (attemptLimiter.recordLoginFailure(userId) == LOGIN_ALERT_THRESHOLD) {
                eventPublisher.publishEvent(new MfaAlertRequested(user.getEmail()));
            }
            throw new InvalidLoginMfaCodeException();
        }
        attemptLimiter.reset(userId);
    }

    /**
     * @param userId utente da controllare
     * @return {@code true} se l'utente ha la verifica in due passaggi attiva
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        return userMfaRepository.findById(userId).map(UserMfa::isEnabled).orElse(false);
    }

    /**
     * Cancella configurazione e codici di recupero di un utente. La chiama {@code UserAccountService} durante
     * la cancellazione dell'account, prima degli effetti irreversibili.
     *
     * @param userId utente da ripulire
     */
    @Transactional
    public void deleteAllOf(UUID userId) {
        userMfaRepository.deleteAllOfUser(userId);
        recoveryCodeRepository.deleteAllOfUser(userId);
    }

    /**
     * Azzera la verifica in due passaggi di un altro utente, per chi ha perso sia il telefono sia i codici di
     * recupero. L'identità del titolare la verifica l'amministratore fuori dall'app: il sistema si fida
     * dell'{@code ADMIN} e per questo lascia una traccia nel log. Chiude anche tutte le sessioni dell'utente.
     *
     * @param adminId amministratore che esegue l'azzeramento
     * @param email email dell'utente da azzerare
     * @throws ResourceNotFoundException se l'utente non esiste o non ha la verifica in due passaggi attiva
     *         (stesso messaggio nei due casi)
     * @throws BusinessRuleViolationException se l'amministratore prova ad azzerare la propria
     */
    @Transactional
    public void adminReset(UUID adminId, String email) {
        User target = userRepository.findByEmail(User.normalizeEmail(email))
                .orElseThrow(() -> new ResourceNotFoundException("Two-factor authentication is not enabled for this user"));
        UUID userId = target.getId();
        if (userId.equals(adminId)) {
            throw new BusinessRuleViolationException("An administrator cannot reset their own two-factor authentication");
        }
        lockUser(userId);
        boolean enabled = userMfaRepository.findById(userId).map(UserMfa::isEnabled).orElse(false);
        if (!enabled) {
            throw new ResourceNotFoundException("Two-factor authentication is not enabled for this user");
        }

        Instant now = clock.instant();
        userMfaRepository.deleteAllOfUser(userId);
        recoveryCodeRepository.deleteAllOfUser(userId);
        // Come per la cancellazione dell'account, l'azzeramento non lascia sessioni aperte su nessun dispositivo
        refreshTokenRepository.revokeAllOfUser(userId, now);
        // Redis per ultimo: se non risponde, l'eccezione riporta indietro anche le scritture sul database
        tokenBlacklist.revokeAllIssuedUntil(userId, now);
        // Il titolare deve poter riattivare subito la verifica, anche se qualcuno aveva esaurito i suoi tentativi
        attemptLimiter.reset(userId);
        eventPublisher.publishEvent(new MfaAdminResetRequested(target.getEmail()));
        // Nel log solo gli id, mai l'email
        log.info("Two-factor reset by administrator {} for user {}", adminId, userId);
    }

    /**
     * Consuma un tentativo dal limite delle operazioni in sessione. Ogni codice inviato conta; il contatore si
     * azzera solo con un codice giusto.
     *
     * @param userId utente autenticato
     * @throws RateLimitExceededException se i tentativi sono esauriti
     */
    private void acquireInSessionAttempt(UUID userId) {
        attemptLimiter.tryAcquireInSession(userId).ifPresent(retryAfter -> {
            throw new RateLimitExceededException(retryAfter);
        });
    }

    /**
     * Verifica un codice che può essere un TOTP (6 cifre) o un codice di recupero. Un codice di recupero
     * valido viene consumato.
     *
     * @param mfa configurazione dell'utente
     * @param userId utente
     * @param code codice inserito
     * @param now istante attuale
     * @throws InvalidMfaCodeException se il codice non è valido
     */
    private void verifyTotpOrRecovery(UserMfa mfa, UUID userId, String code, Instant now) {
        String trimmed = code.strip();
        if (TOTP_FORMAT.matcher(trimmed).matches()) {
            mfa.useStep(checkTotp(mfa, trimmed, now));
        } else {
            consumeRecoveryCode(userId, trimmed, now);
        }
    }

    /**
     * Controlla un codice TOTP contro il segreto decifrato, rifiutando i passi già usati.
     *
     * @param mfa configurazione dell'utente
     * @param code codice di 6 cifre
     * @param now istante attuale
     * @return il passo che ha combaciato, da segnare come usato
     * @throws InvalidMfaCodeException se il codice è sbagliato o il passo è già stato usato
     */
    private long checkTotp(UserMfa mfa, String code, Instant now) {
        byte[] secret = cipher.decrypt(mfa.getSecretEnc());
        long step = Totp.matchingStep(secret, code, now);
        if (step < 0 || !mfa.isStepFresh(step)) {
            throw new InvalidMfaCodeException();
        }
        return step;
    }

    /**
     * Consuma un codice di recupero. L'UPDATE condizionale del repository rende il consumo atomico: due
     * richieste concorrenti con lo stesso codice non possono riuscire entrambe.
     *
     * @param userId utente
     * @param code codice di recupero come l'ha scritto l'utente
     * @param now istante attuale
     * @throws InvalidMfaCodeException se il codice è malformato, sconosciuto o già usato
     */
    private void consumeRecoveryCode(UUID userId, String code, Instant now) {
        String normalized = PasswordResetCode.normalize(code);
        if (!PasswordResetCode.isWellFormed(normalized)) {
            throw new InvalidMfaCodeException();
        }
        if (recoveryCodeRepository.markUsedIfAvailable(userId, sha256Hex(normalized), now) != 1) {
            throw new InvalidMfaCodeException();
        }
    }

    /**
     * @param code codice inserito
     * @return il codice senza spazi ai bordi
     * @throws InvalidMfaCodeException se non è un codice di 6 cifre
     */
    private String requireTotpFormat(String code) {
        String trimmed = code.strip();
        if (!TOTP_FORMAT.matcher(trimmed).matches()) {
            throw new InvalidMfaCodeException();
        }
        return trimmed;
    }

    /**
     * Genera un nuovo set di codici di recupero e salva solo i loro hash. Il set precedente va cancellato
     * prima: la DELETE in blocco parte subito, mentre i {@code save} arrivano al database solo al flush.
     *
     * @param userId utente
     * @param now istante di creazione
     * @return i codici in chiaro, formattati per essere mostrati
     */
    private List<String> issueRecoveryCodes(UUID userId, Instant now) {
        recoveryCodeRepository.deleteAllOfUser(userId);
        List<String> shown = new ArrayList<>(RECOVERY_CODES);
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String normalized = PasswordResetCode.generate(RANDOM);
            shown.add(PasswordResetCode.display(normalized));
            recoveryCodeRepository.save(new MfaRecoveryCode(userId, sha256Hex(normalized), now));
        }
        return shown;
    }

    /**
     * Legge l'utente con un lock in scrittura sulla sua riga, per serializzare le operazioni sullo stesso account.
     *
     * @param userId utente
     * @return l'utente bloccato
     * @throws ResourceNotFoundException se l'utente non esiste
     */
    private User lockUser(UUID userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    /**
     * @param userId utente
     * @return la configurazione avviata (attiva o no)
     * @throws BusinessRuleViolationException se la configurazione non è mai stata avviata
     */
    private UserMfa findSetup(UUID userId) {
        return userMfaRepository.findById(userId)
                .orElseThrow(() -> new BusinessRuleViolationException("Two-factor setup has not been started"));
    }

    /**
     * @param userId utente
     * @return la configurazione attiva
     * @throws BusinessRuleViolationException se la verifica in due passaggi non è attiva
     */
    private UserMfa findEnabled(UUID userId) {
        return userMfaRepository.findById(userId)
                .filter(UserMfa::isEnabled)
                .orElseThrow(() -> new BusinessRuleViolationException("Two-factor authentication is not enabled"));
    }

    /**
     * @param input testo da hashare
     * @return l'hash SHA-256 in esadecimale minuscolo
     */
    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }
}
