package it.unical.geosentinel.emergency.domain;

import java.util.Objects;
import java.util.UUID;

// Squadra inviata o allerta risolta: l'escursionista riceve una push. hikerId serve solo a trovare i suoi
// dispositivi e non va nei log (dato personale, come nel SosAlertRaisedEvent)
public record SosAlertStatusChangedEvent(
        UUID alertId,
        UUID hikerId,
        SosStatus status
) {
    public SosAlertStatusChangedEvent {
        Objects.requireNonNull(alertId, "alertId cannot be null");
        Objects.requireNonNull(hikerId, "hikerId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
    }
}
