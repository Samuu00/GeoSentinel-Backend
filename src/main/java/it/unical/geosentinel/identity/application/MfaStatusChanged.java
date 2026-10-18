package it.unical.geosentinel.identity.application;

// 2FA attivata o disattivata dall'utente: l'email parte dopo il commit (MfaAlertMailListener).
// Chi ruba un access token può attivarla e chiudere fuori il titolare: l'avviso glielo dice subito
public record MfaStatusChanged(String email, boolean enabled) {
    @Override
    public String toString() {
        return "MfaStatusChanged[email=***, enabled=" + enabled + "]";
    }
}
