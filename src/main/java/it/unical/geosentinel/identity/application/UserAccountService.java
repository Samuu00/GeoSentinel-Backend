package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.emergency.application.SosService;
import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.identity.infrastructure.persistence.RefreshTokenRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserRepository;
import it.unical.geosentinel.media.application.MediaService;
import it.unical.geosentinel.notification.application.DeviceService;
import it.unical.geosentinel.refuge.application.RefugeBookingService;
import it.unical.geosentinel.shared.domain.exception.AccountManagedException;
import it.unical.geosentinel.shared.domain.exception.ResourceNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

// Profilo e cancellazione dell'account. Il cambio password sta in AuthService: apre una nuova sessione
@Service
public class UserAccountService {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklist tokenBlacklist;
    private final RefugeBookingService refugeBookingService;
    private final SosService sosService;
    private final MediaService mediaService;
    private final DeviceService deviceService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserAccountService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            TokenBlacklist tokenBlacklist,
            RefugeBookingService refugeBookingService,
            SosService sosService,
            MediaService mediaService,
            DeviceService deviceService,
            PasswordEncoder passwordEncoder,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenBlacklist = tokenBlacklist;
        this.refugeBookingService = refugeBookingService;
        this.sosService = sosService;
        this.mediaService = mediaService;
        this.deviceService = deviceService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserProfile getProfile(UUID userId) {
        return UserProfile.from(activeUser(userId));
    }

    @Transactional
    public UserProfile updateDisplayName(UUID userId, String displayName) {
        User user = activeUser(userId);
        user.updateProfile(displayName);
        return UserProfile.from(user);
    }

    // Solo HIKER: gli account del personale li chiude l'organizzazione (rifugi, ticket e missioni in carico) -> 409.
    // Le altre feature puliscono i propri dati dal loro service, in questa stessa transazione.
    // Ordine: prima tutto ciò che torna indietro con un rollback, poi la blacklist (Redis giù -> rollback, nulla perso),
    // per ultimi gli oggetti dello storage, l'unico passo irreversibile
    @Transactional
    public void deleteAccount(UUID userId, Jwt accessToken) {
        User user = activeUser(userId);
        if (user.getRole() != Role.HIKER) {
            throw new AccountManagedException("Staff accounts are closed by an administrator");
        }

        Instant now = clock.instant();
        refreshTokenRepository.revokeAllOfUser(userId, now);
        refugeBookingService.cancelAllFutureOf(userId);
        sosService.cancelActiveOf(userId);
        deviceService.deleteAllOf(userId);
        // hash vero di un valore casuale mai restituito: nessuna password può più coincidere
        user.markDeleted(now, passwordEncoder.encode(UUID.randomUUID().toString()));
        // @Version: una seconda cancellazione concorrente fallisce qui (409), prima di Redis e dello storage
        userRepository.flush();

        if (accessToken.getId() != null && accessToken.getExpiresAt() != null && accessToken.getExpiresAt().isAfter(now)) {
            tokenBlacklist.revoke(accessToken.getId(), Duration.between(now, accessToken.getExpiresAt()));
        }
        // Anche gli access token degli altri dispositivi: altrimenti per 15 minuti potrebbero ancora prenotare,
        // lanciare SOS o caricare foto (che deleteAllOf non vedrebbe più) a nome di un account cancellato
        tokenBlacklist.revokeAllIssuedUntil(userId, now);
        mediaService.deleteAllOf(userId);
    }

    // Un account cancellato o disabilitato non esiste più per l'API (il suo access token è già in blacklist)
    private User activeUser(UUID userId) {
        return userRepository.findById(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}
