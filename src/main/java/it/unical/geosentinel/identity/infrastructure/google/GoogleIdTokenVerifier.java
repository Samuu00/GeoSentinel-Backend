/*
 * Implementazione della porta GoogleIdentityVerifier: verifica un ID token di Google Sign-In e ne estrae
 * l'identità dell'utente.
 */
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

/**
 * Verificatore degli ID token di Google. Il {@link JwtDecoder} controlla la firma RS256 con le chiavi pubbliche
 * di Google e la scadenza; questa classe aggiunge i controlli su emittente, destinatario e claim obbligatori.
 * Lo crea {@link GoogleConfig}.
 */
public class GoogleIdTokenVerifier implements GoogleIdentityVerifier {
    /**
     * Emittenti accettati: Google usa entrambe le forme. Il claim si legge come stringa perché
     * {@code Jwt.getIssuer()} lo converte in URL e lancerebbe un'eccezione (quindi un 500) sulla forma senza schema.
     */
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    /** Lunghezza massima dell'email, uguale alle colonne del database: oltre, l'INSERT fallirebbe con un 500. */
    private static final int MAX_EMAIL_LENGTH = 254;

    private final Set<String> clientIds;
    private final JwtDecoder jwtDecoder;

    /**
     * @param clientIds client ID accettati come destinatari; quelli vuoti vengono scartati
     * @param jwtDecoder decoder configurato con le chiavi pubbliche di Google
     */
    public GoogleIdTokenVerifier(Set<String> clientIds, JwtDecoder jwtDecoder) {
        // Un client ID vuoto non è valido e non deve combaciare con nulla
        this.clientIds = clientIds.stream().map(String::strip).filter(id -> !id.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.jwtDecoder = jwtDecoder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GoogleIdentity verify(String idToken){
        Jwt jwt = decode(idToken);

        if (!ISSUERS.contains(jwt.getClaimAsString("iss"))) {
            throw new BadCredentialsException("Invalid Google ID token: issuer");
        }
        if (jwt.getAudience() == null || jwt.getAudience().stream().noneMatch(clientIds::contains)) {
            throw new BadCredentialsException("Invalid Google ID token: audience");
        }
        // Il validatore predefinito controlla exp solo se è presente: un token senza scadenza varrebbe per sempre
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

    /**
     * Decodifica e verifica la firma del token.
     *
     * @param idToken token ricevuto dall'app
     * @return il JWT decodificato
     * @throws IdentityProviderUnavailableException se le chiavi pubbliche di Google non sono raggiungibili
     * @throws BadCredentialsException se il token non è valido
     */
    private Jwt decode(String idToken) {
        try {
            return jwtDecoder.decode(idToken);
        } catch (JwtException ex) {
            // Se le chiavi pubbliche non si scaricano non è colpa del token: si risponde 503 e non 401
            if (causedByUnreachableKeys(ex)) {
                throw new IdentityProviderUnavailableException("Google public keys unreachable", ex);
            }
            throw new BadCredentialsException("Invalid Google ID token", ex);
        }
    }

    /**
     * @param ex eccezione del decoder
     * @return {@code true} se tra le cause c'è l'impossibilità di scaricare le chiavi di Google
     */
    private static boolean causedByUnreachableKeys(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof RemoteKeySourceException) {
                return true;
            }
        }
        return false;
    }
}
