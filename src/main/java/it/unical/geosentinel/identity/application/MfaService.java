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

@Service
public class MfaService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOTP_FORMAT = Pattern.compile("\\d{6}");
    private static final int SECRET_BYTES = 20; // 160 bit
    private static final int RECOVERY_CODES = 10;
    private static final long LOGIN_ALERT_THRESHOLD = 3; // email d'avviso al terzo errore del login nell'ora
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
        // ADR-0026: la registrazione non verifica l'email. Il codice mandato lì prova che chi attiva la 2FA ne è il
        // titolare: chi ha registrato l'email di un altro non può chiuderlo fuori quando entra con Google
        String emailCode = PasswordResetCode.generate(RANDOM);
        mfa.issueEmailCode(sha256Hex(emailCode), now.plus(SETUP_EMAIL_CODE_TTL));
        eventPublisher.publishEvent(new MfaSetupCodeRequested(user.getEmail(), PasswordResetCode.display(emailCode), SETUP_EMAIL_CODE_TTL));
        return new MfaSetupView(Totp.encodeSecret(secret), Totp.otpauthUri(user.getEmail(), secret));
    }

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
        mfa.useStep(checkTotp(mfa, requireTotpFormat(code), now)); // il codice di conferma non vale anche al login
        attemptLimiter.reset(userId);
        mfa.enable(now);
        eventPublisher.publishEvent(new MfaStatusChanged(user.getEmail(), true));
        return issueRecoveryCodes(userId, now);
    }

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

    // ADR-0026: un'operazione in sessione che apre una sessione nuova (cambio password) chiede il secondo fattore come
    // il login. Senza 2FA non fa nulla. Codice assente o sbagliato -> 422 (un 401 farebbe credere all'app che il token
    // sia scaduto); il bucket è quello delle operazioni in sessione
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


    // Chiamato dal login dopo la password giusta: senza 2FA non fa nulla.
    // noRollbackFor: l'errore deve restare committato (contatore ed evento d'avviso), come in AuthService.login
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
            // Solo il tipo di evento e l'email: nessun IP né orario nell'avviso
            if (attemptLimiter.recordLoginFailure(userId) == LOGIN_ALERT_THRESHOLD) {
                eventPublisher.publishEvent(new MfaAlertRequested(user.getEmail()));
            }
            throw new InvalidLoginMfaCodeException();
        }
        attemptLimiter.reset(userId);
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        return userMfaRepository.findById(userId).map(UserMfa::isEnabled).orElse(false);
    }

    // Cancellazione dell'account: stesso ordine di UserAccountService (effetti irreversibili per ultimi)
    @Transactional
    public void deleteAllOf(UUID userId) {
        userMfaRepository.deleteAllOfUser(userId);
        recoveryCodeRepository.deleteAllOfUser(userId);
    }

    // Verifica dell'identità fatta fuori dall'app: qui ci si fida dell'ADMIN, per questo si lascia una traccia nel log
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
        // Stessa regola della cancellazione dell'account: il reset non lascia sessioni aperte, anche sugli altri dispositivi
        refreshTokenRepository.revokeAllOfUser(userId, now);
        // Redis per ultimo: se cade, la transazione torna indietro
        tokenBlacklist.revokeAllIssuedUntil(userId, now);
        // Il titolare deve poter riattivare la 2FA subito, anche se qualcuno aveva svuotato i bucket
        attemptLimiter.reset(userId);
        eventPublisher.publishEvent(new MfaAdminResetRequested(target.getEmail()));
        // Solo gli id: mai l'email nel log
        log.info("Two-factor reset by administrator {} for user {}", adminId, userId);
    }

    // Ogni codice inviato conta: il bucket si azzera solo con un codice giusto
    private void acquireInSessionAttempt(UUID userId) {
        attemptLimiter.tryAcquireInSession(userId).ifPresent(retryAfter -> {
            throw new RateLimitExceededException(retryAfter);
        });
    }

    // TOTP (6 cifre) o codice di recupero; un codice di recupero si consuma
    private void verifyTotpOrRecovery(UserMfa mfa, UUID userId, String code, Instant now) {
        String trimmed = code.strip();
        if (TOTP_FORMAT.matcher(trimmed).matches()) {
            mfa.useStep(checkTotp(mfa, trimmed, now));
        } else {
            consumeRecoveryCode(userId, trimmed, now);
        }
    }

    private long checkTotp(UserMfa mfa, String code, Instant now) {
        byte[] secret = cipher.decrypt(mfa.getSecretEnc());
        long step = Totp.matchingStep(secret, code, now);
        if (step < 0 || !mfa.isStepFresh(step)) {
            throw new InvalidMfaCodeException();
        }
        return step;
    }

    private void consumeRecoveryCode(UUID userId, String code, Instant now) {
        String normalized = PasswordResetCode.normalize(code);
        if (!PasswordResetCode.isWellFormed(normalized)) {
            throw new InvalidMfaCodeException();
        }
        if (recoveryCodeRepository.markUsedIfAvailable(userId, sha256Hex(normalized), now) != 1) {
            throw new InvalidMfaCodeException();
        }
    }

    private String requireTotpFormat(String code) {
        String trimmed = code.strip();
        if (!TOTP_FORMAT.matcher(trimmed).matches()) {
            throw new InvalidMfaCodeException();
        }
        return trimmed;
    }

    // Il vecchio set di codici va cancellato prima dell'inserimento: il bulk esegue subito, il save si fa al flush
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

    private User lockUser(UUID userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private UserMfa findSetup(UUID userId) {
        return userMfaRepository.findById(userId)
                .orElseThrow(() -> new BusinessRuleViolationException("Two-factor setup has not been started"));
    }

    private UserMfa findEnabled(UUID userId) {
        return userMfaRepository.findById(userId)
                .filter(UserMfa::isEnabled)
                .orElseThrow(() -> new BusinessRuleViolationException("Two-factor authentication is not enabled"));
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }
}