package it.unical.geosentinel.identity.application;

import java.time.Duration;

// ADR-0026: codice dello setup della 2FA, spedito all'email dopo il commit (MfaAlertMailListener).
// toString senza dati: né l'email né il codice finiscono nei log
public record MfaSetupCodeRequested(String email, String code, Duration validFor) {
    @Override
    public String toString() {
        return "MfaSetupCodeRequested[email=***, code=***]";
    }
}
