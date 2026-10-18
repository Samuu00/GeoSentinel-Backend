package it.unical.geosentinel.identity.application.port;

// Porta verso un sistema esterno (Google, come ObjectStorage e TokenBlacklist)
public interface GoogleIdentityVerifier {
    // BadCredentialsException se il token non è valido, IdentityProviderUnavailableException se Google non risponde
    GoogleIdentity verify(String idToken);
}
