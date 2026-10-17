package it.unical.geosentinel.identity.application;

// Richiesta di reset accettata dal limite: l'email (già normalizzata) passa a PasswordResetMailListener, che emette e
// spedisce il codice in background. Nessun codice qui: lo genera il listener
public record PasswordResetRequested(String normalizedEmail) {
    @Override
    public String toString() {
        return "PasswordResetRequested[email=***]";
    }
}
