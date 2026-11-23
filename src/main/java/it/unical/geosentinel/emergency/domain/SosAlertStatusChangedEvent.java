// Evento di dominio pubblicato quando un'allerta SOS passa a DISPATCHED (squadra inviata) o a RESOLVED.
// SosNotificationListener lo usa per mandare una push all'escursionista che ha chiesto aiuto.
package it.unical.geosentinel.emergency.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Cambio di stato di un'allerta da comunicare all'escursionista.
 * L'id dell'escursionista serve solo a trovare i suoi dispositivi registrati e non va scritto nei log,
 * perché è un dato personale.
 *
 * @param alertId id dell'allerta
 * @param hikerId id dell'escursionista da avvisare
 * @param status nuovo stato dell'allerta
 */
public record SosAlertStatusChangedEvent(
        UUID alertId,
        UUID hikerId,
        SosStatus status
) {
    /**
     * Controlla che tutti i campi siano presenti.
     *
     * @throws NullPointerException se uno dei campi è null
     */
    public SosAlertStatusChangedEvent {
        Objects.requireNonNull(alertId, "alertId cannot be null");
        Objects.requireNonNull(hikerId, "hikerId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
    }
}
