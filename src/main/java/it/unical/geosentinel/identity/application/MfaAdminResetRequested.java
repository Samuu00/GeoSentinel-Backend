package it.unical.geosentinel.identity.application;

// Reset della 2FA da ADMIN: l'email all'utente parte dopo il commit (MfaAlertMailListener)
public record MfaAdminResetRequested(String email) {
    @Override
    public String toString() {
        return "MfaAdminResetRequested[email=***]";
    }
}