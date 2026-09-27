package it.unical.geosentinel.emergency.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

// Solo dati, niente entità né geometrie JTS: il listener gira dopo il commit, fuori dalla transazione.
// Niente note mediche né id dell'escursionista: sono dati personali e finirebbero nei log; il listener li rilegge per id
public record SosAlertRaisedEvent(
        UUID alertId,
        double lon,
        double lat,
        Instant raisedAt
) {
    public SosAlertRaisedEvent {
        Objects.requireNonNull(alertId, "alertId cannot be null");
        Objects.requireNonNull(raisedAt, "raisedAt cannot be null");
    }
}
