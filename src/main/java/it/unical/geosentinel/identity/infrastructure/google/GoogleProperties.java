/*
 * Proprietà del login con Google, lette dal prefisso geosentinel.google. Contengono solo dati pubblici
 * (client ID e indirizzo delle chiavi di Google): nessun segreto.
 */
package it.unical.geosentinel.identity.infrastructure.google;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

/**
 * Configurazione del login con Google.
 *
 * @param clientIds client ID OAuth delle app (Android, iOS, web) accettati come destinatari del token
 * @param jwkSetUri indirizzo del set di chiavi pubbliche (JWK) con cui Google firma gli ID token
 */
@ConfigurationProperties("geosentinel.google")
public record GoogleProperties(List<String> clientIds, String jwkSetUri) {
    /**
     * Con la variabile {@code GOOGLE_CLIENT_IDS} vuota o assente la lista diventa vuota e non {@code null}:
     * in quel caso ogni token viene rifiutato.
     */
    public GoogleProperties {
        clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
    }
}
