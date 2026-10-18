package it.unical.geosentinel.identity.application;

// Terzo errore del login con la verifica in due passaggi: l'email arriva a MfaAlertMailListener dopo il commit
public record MfaAlertRequested(String email) {
    @Override
    public String toString() {
        return "MfaAlertRequested[email=***]";
    }
}