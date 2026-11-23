/*
 * DTO degli endpoint della verifica in due passaggi (MfaController e AdminMfaController), raccolti in
 * un'unica classe contenitore.
 */
package it.unical.geosentinel.identity.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import it.unical.geosentinel.identity.application.MfaSetupView;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Contenitore dei record di richiesta e risposta della verifica in due passaggi.
 */
public class MfaDtos {
    /**
     * Risposta all'avvio della configurazione.
     *
     * @param secret segreto TOTP in Base32, per l'inserimento a mano nell'app di autenticazione
     * @param otpauthUri URI {@code otpauth://} che il client mostra come QR code
     */
    public record MfaSetupResponse(
            @Schema(requiredMode = REQUIRED)
            String secret,
            @Schema(requiredMode = REQUIRED)
            String otpauthUri
    ) {
        /**
         * @param view dati restituiti dal service
         * @return la risposta HTTP
         */
        public static MfaSetupResponse from(MfaSetupView view) {
            return new MfaSetupResponse(view.secret(), view.otpauthUri());
        }
    }

    /**
     * Richiesta con un codice di verifica.
     *
     * @param code codice TOTP di 6 cifre; per la disattivazione è accettato anche un codice di recupero
     */
    public record MfaCodeRequest(
            @NotBlank(message = "MFA code is required")
            @Size(max = 32, message = "MFA code must be at most 32 characters long")
            String code
    ) {}

    /**
     * Richiesta di attivazione.
     *
     * @param code codice TOTP dell'app di autenticazione
     * @param emailCode codice ricevuto via email all'avvio della configurazione
     */
    public record MfaEnableRequest(
            @NotBlank(message = "MFA code is required")
            @Size(max = 32, message = "MFA code must be at most 32 characters long")
            String code,
            @NotBlank(message = "Email code is required")
            @Size(max = 32, message = "Email code must be at most 32 characters long")
            String emailCode
    ) {}

    /**
     * Codici di recupero appena generati, mostrati all'utente una sola volta.
     *
     * @param codes codici in chiaro
     */
    public record RecoveryCodesResponse(
            @Schema(requiredMode = REQUIRED)
            List<String> codes
    ){}

    /**
     * Richiesta di azzeramento da parte di un amministratore.
     *
     * @param email email dell'utente da azzerare
     */
    public record AdminMfaResetRequest(
            @NotBlank(message = "User email is required")
            @Email(message = "Invalid email format")
            @Size(max = 254, message = "Email must be at most 254 characters long")
            String email
    ) {}
}
