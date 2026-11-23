// Test unitario del logout in AuthService, con dipendenze finte di Mockito e orologio fisso, senza Spring.
// Al logout il client manda il proprio refresh token: il servizio revoca l'intera famiglia di token nata da
// quel login, ma solo se il token appartiene davvero all'utente autenticato.
package it.unical.geosentinel.identity.application;

import it.unical.geosentinel.notification.application.DeviceService;
import it.unical.geosentinel.identity.application.port.LoginAttemptLimiter;
import it.unical.geosentinel.identity.application.port.TokenBlacklist;
import it.unical.geosentinel.identity.domain.RefreshToken;
import it.unical.geosentinel.identity.domain.User;
import it.unical.geosentinel.identity.infrastructure.persistence.RefreshTokenRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserIdentityRepository;
import it.unical.geosentinel.identity.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifica che il logout revochi la famiglia del refresh token solo quando il token è dell'utente che fa
 * la richiesta.
 */
class AuthServiceLogoutTest {

    /** Istante fisso restituito dall'orologio del servizio. */
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    /** Repository dei refresh token, su cui si verifica la revoca. */
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);

    /** Servizio sotto test; tutte le dipendenze non coinvolte nel logout sono finte. */
    private final AuthService service = new AuthService(
            mock(UserIdentityRepository.class), mock(UserRepository.class), refreshTokens, mock(PasswordEncoder.class), mock(TokenBlacklist.class),
            mock(LoginAttemptLimiter.class), mock(MfaService.class), mock(DeviceService.class), mock(AuthService.JwtIssuer.class), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7), Duration.ofDays(30), Duration.ofSeconds(60), 14);

    /** Id del proprietario del refresh token salvato. */
    private final UUID ownerId = UUID.randomUUID();

    /** Id della famiglia di token a cui appartiene il refresh token salvato. */
    private final UUID familyId = UUID.randomUUID();

    /**
     * Configura il repository finto in modo che qualsiasi refresh token cercato risulti salvato e appartenente
     * all'utente indicato, nella famiglia {@link #familyId}.
     *
     * @param userId l'id del proprietario del token
     */
    private void storedTokenOwnedBy(UUID userId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        RefreshToken token = mock(RefreshToken.class);
        when(token.getUser()).thenReturn(user);
        when(token.getFamilyId()).thenReturn(familyId);
        when(refreshTokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));
    }

    /**
     * Costruisce un access token JWT già validato per l'utente indicato.
     *
     * @param subject l'id dell'utente autenticato
     * @return il JWT con scadenza dieci minuti dopo {@link #NOW}
     */
    private Jwt jwtFor(UUID subject) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(subject.toString())
                .jti("jti").issuedAt(NOW).expiresAt(NOW.plusSeconds(600)).build();
    }

    /** Se il refresh token appartiene all'utente autenticato, la sua famiglia deve essere revocata all'istante corrente. */
    @Test
    void revocaLaFamigliaDelProprioToken() {
        storedTokenOwnedBy(ownerId);
        service.logout(jwtFor(ownerId), "raw");
        verify(refreshTokens).revokeFamily(familyId, NOW);
    }

    /**
     * Se il refresh token appartiene a un altro utente, il logout non deve revocare nulla: un utente non può
     * chiudere la sessione di qualcun altro mandando un suo token.
     */
    @Test
    void ignoraIlTokenDiUnAltroUtente() {
        storedTokenOwnedBy(ownerId);
        service.logout(jwtFor(UUID.randomUUID()), "raw");
        verify(refreshTokens, never()).revokeFamily(any(), any());
    }
}
