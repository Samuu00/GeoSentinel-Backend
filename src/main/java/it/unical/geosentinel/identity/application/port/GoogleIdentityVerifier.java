/*
 * Porta verso Google per la verifica degli ID token. Come ObjectStorage e TokenBlacklist, separa la logica
 * applicativa dal sistema esterno: l'implementazione sta in infrastructure/google.
 */
package it.unical.geosentinel.identity.application.port;

/**
 * Verificatore degli ID token emessi da Google Sign-In.
 */
public interface GoogleIdentityVerifier {
    /**
     * Verifica firma, emittente, destinatario e scadenza di un ID token.
     *
     * @param idToken ID token ricevuto dall'app
     * @return l'identità contenuta nel token
     * @throws org.springframework.security.authentication.BadCredentialsException se il token non è valido
     * @throws it.unical.geosentinel.shared.domain.exception.IdentityProviderUnavailableException se Google non risponde
     */
    GoogleIdentity verify(String idToken);
}
