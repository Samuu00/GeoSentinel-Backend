package it.unical.geosentinel.identity.infrastructure.google;

import com.nimbusds.jose.RemoteKeySourceException;
import it.unical.geosentinel.identity.application.port.GoogleIdentity;
import it.unical.geosentinel.identity.application.port.GoogleIdentityVerifier;
import it.unical.geosentinel.shared.domain.exception.IdentityProviderUnavailableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Set;
import java.util.stream.Collectors;

// Il decoder verifica firma (RS256, chiavi di Google) e scadenza; qui issuer, audience e claim obbligatori
public class GoogleIdTokenVerifier implements GoogleIdentityVerifier {
    // Google usa entrambe le forme. Si legge come stringa: Jwt.getIssuer() converte in URL e lancia sulla forma senza schema (500)
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    // Stesso limite di users.email e user_identities.email: oltre, l'INSERT fallirebbe con un 500
    private static final int MAX_EMAIL_LENGTH = 254;

    private final Set<String> clientIds;
    private final JwtDecoder jwtDecoder;

    public GoogleIdTokenVerifier(Set<String> clientIds, JwtDecoder jwtDecoder) {
        // Un client ID vuoto non è valido
        this.clientIds = clientIds.stream().map(String::strip).filter(id -> !id.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public GoogleIdentity verify(String idToken){
        Jwt jwt = decode(idToken);

        if (!ISSUERS.contains(jwt.getClaimAsString("iss"))) {
            throw new BadCredentialsException("Invalid Google ID token: issuer");
        }
        if (jwt.getAudience() == null || jwt.getAudience().stream().noneMatch(clientIds::contains)) {
            throw new BadCredentialsException("Invalid Google ID token: audience");
        }
        // Il validatore di default controlla exp solo se c'è: un token senza scadenza varrebbe per sempre
        if (jwt.getExpiresAt() == null) {
            throw new BadCredentialsException("Invalid Google ID token: missing expiration");
        }
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (subject == null || subject.isBlank() || email == null || email.isBlank() || email.length() > MAX_EMAIL_LENGTH) {
            throw new BadCredentialsException("Invalid Google ID token: missing subject or email");
        }

        return new GoogleIdentity(
                subject,
                email,
                Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")),
                jwt.getClaimAsString("name"));
    }

    private Jwt decode(String idToken) {
        try {
            return jwtDecoder.decode(idToken);
        } catch (JwtException ex) {
            // Le chiavi pubbliche non si scaricano: non è colpa del token, quindi 503 e non 401
            if (causedByUnreachableKeys(ex)) {
                throw new IdentityProviderUnavailableException("Google public keys unreachable", ex);
            }
            throw new BadCredentialsException("Invalid Google ID token", ex);
        }
    }

    private static boolean causedByUnreachableKeys(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof RemoteKeySourceException) {
                return true;
            }
        }
        return false;
    }
}
