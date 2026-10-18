package it.unical.geosentinel.identity.infrastructure.google;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

// Solo dati pubblici: i client ID e l'indirizzo delle chiavi di Google. Nessun segreto (ADR-0024)
@ConfigurationProperties("geosentinel.google")
public record GoogleProperties(List<String> clientIds, String jwkSetUri) {
    public GoogleProperties {
        // GOOGLE_CLIENT_IDS vuota o assente: lista vuota (ogni token rifiutato), non null
        clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
    }
}
