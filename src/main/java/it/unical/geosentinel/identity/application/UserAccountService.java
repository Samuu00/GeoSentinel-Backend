/*
 * Servizio applicativo dell'account dell'utente autenticato: lettura e modifica del profilo e cancellazione
 * dell'account. Il cambio password sta invece in AuthService, perché apre una nuova sessione.
 */
package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.emergency.application.SosService;
import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import it.unical.geosentinel.identity.domain.Role;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.identity.infrastructure.persistence.RefreshTokenRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.PasswordResetTokenRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserIdentityRepository;
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

/**
 * Profilo e cancellazione dell'account, usato da {@code UserController}. La cancellazione coordina i service
 * degli altri moduli (rifugi, SOS, media, notifiche) perché ognuno ripulisca i propri dati.
 */
@Service
public class UserAccountService {
    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokens;
    private final TokenBlacklist tokenBlacklist;
    private final RefugeBookingService refugeBookingService;
    private final SosService sosService;
    private final MediaService mediaService;
    private final DeviceService deviceService;
    private final MfaService mfaService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserAccountService(
            UserRepository userRepository,
            UserIdentityRepository userIdentityRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordResetTokenRepository passwordResetTokens,
            TokenBlacklist tokenBlacklist,
            RefugeBookingService refugeBookingService,
            SosService sosService,
            MediaService mediaService,
            DeviceService deviceService,
            MfaService mfaService,
            PasswordEncoder passwordEncoder,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokens = passwordResetTokens;
        this.tokenBlacklist = tokenBlacklist;
        this.refugeBookingService = refugeBookingService;
        this.sosService = sosService;
        this.mediaService = mediaService;
        this.deviceService = deviceService;
        this.mfaService = mfaService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * @param userId utente autenticato
     * @return il profilo dell'utente
     * @throws ResourceNotFoundException se l'utente non esiste o è disabilitato
     */
    @Transactional(readOnly = true)
    public UserProfile getProfile(UUID userId) {
        return profileOf(activeUser(userId));
    }

    /**
     * Cambia il nome mostrato nell'app.
     *
     * @param userId utente autenticato
     * @param displayName nuovo nome, già validato dal DTO
     * @return il profilo aggiornato
     * @throws ResourceNotFoundException se l'utente non esiste o è disabilitato
     */
    @Transactional
    public UserProfile updateDisplayName(UUID userId, String displayName) {
        User user = activeUser(userId);
        user.updateProfile(displayName);
        return profileOf(user);
    }

    /**
     * Costruisce il profilo aggiungendo lo stato della verifica in due passaggi e la foto profilo. La foto è un
     * media di tipo {@code USER}: qui si restituisce solo il suo id, l'immagine si scarica con
     * {@code /media/{id}/download-url}.
     *
     * @param user utente
     * @return il profilo completo
     */
    private UserProfile profileOf(User user) {
        UUID avatarId = mediaService.currentAvatarOf(user.getId()).orElse(null);
        return UserProfile.from(user, mfaService.isEnabled(user.getId()), avatarId);
    }

    /**
     * Cancella (anonimizza) l'account dell'utente autenticato. Vale solo per gli escursionisti: gli account del
     * personale li chiude l'organizzazione, perché possono avere in carico rifugi, ticket e missioni.
     * Gli altri moduli ripuliscono i propri dati dai loro service, nella stessa transazione.
     * <p>
     * L'ordine dei passi conta: prima tutto ciò che un rollback può annullare, poi la blacklist su Redis (se Redis
     * non risponde la transazione torna indietro e non si perde nulla), per ultima la cancellazione dei file
     * nello storage, l'unico passo irreversibile.
     *
     * @param userId utente autenticato
     * @param accessToken access token della richiesta, messo subito nella blacklist
     * @throws ResourceNotFoundException se l'utente non esiste o è disabilitato
     * @throws AccountManagedException se l'account è dello staff
     */
    @Transactional
    public void deleteAccount(UUID userId, Jwt accessToken) {
        // Lock come in changePassword: un refresh concorrente non può creare una sessione dopo la revoca
        User user = userRepository.findByIdForUpdate(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (user.getRole() != Role.HIKER) {
            throw new AccountManagedException("Staff accounts are closed by an administrator");
        }

        Instant now = clock.instant();
        refreshTokenRepository.revokeAllOfUser(userId, now);
        refugeBookingService.cancelAllFutureOf(userId);
        sosService.cancelActiveOf(userId);
        deviceService.deleteAllOf(userId);
        // Le identità esterne si tolgono con l'account: altrimenti lo stesso account Google riaprirebbe
        // l'account cancellato
        userIdentityRepository.deleteAllOf(userId);
        passwordResetTokens.deleteAllOf(userId);
        mfaService.deleteAllOf(userId);
        // Hash vero di un valore casuale mai restituito: nessuna password potrà più coincidere
        user.markDeleted(now, passwordEncoder.encode(UUID.randomUUID().toString()));
        // Grazie a @Version una seconda cancellazione concorrente fallisce qui (409), prima di Redis e dello storage
        userRepository.flush();

        if (accessToken.getId() != null && accessToken.getExpiresAt() != null && accessToken.getExpiresAt().isAfter(now)) {
            tokenBlacklist.revoke(accessToken.getId(), Duration.between(now, accessToken.getExpiresAt()));
        }
        // Si revocano anche gli access token degli altri dispositivi: altrimenti fino alla loro scadenza potrebbero
        // ancora prenotare, lanciare SOS o caricare foto (che la pulizia dei media non vedrebbe più) a nome di un
        // account cancellato
        tokenBlacklist.revokeAllIssuedUntil(userId, now);
        mediaService.deleteAllOf(userId);
    }

    /**
     * Legge un utente attivo. Un account cancellato o disabilitato per l'API non esiste più.
     *
     * @param userId utente
     * @return l'utente
     * @throws ResourceNotFoundException se l'utente non esiste o è disabilitato
     */
    private User activeUser(UUID userId) {
        return userRepository.findById(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}
