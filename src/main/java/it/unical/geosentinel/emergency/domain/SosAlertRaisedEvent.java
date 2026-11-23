// Evento di dominio pubblicato quando nasce una nuova allerta SOS.
// Lo riceve SosNotificationListener, che dopo il commit della transazione invia le push agli operatori.
package it.unical.geosentinel.emergency.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Dati di una nuova allerta SOS da notificare agli operatori.
 * <p>
 * Contiene solo valori semplici, senza entità né geometrie JTS, perché il listener gira dopo il commit,
 * fuori dalla transazione in cui l'allerta è stata creata. Non porta le note mediche né l'id
 * dell'escursionista: sono dati personali e, viaggiando nell'evento, rischierebbero di finire nei log.
 * Se servono, il listener rilegge l'allerta dal database tramite il suo id.
 *
 * @param alertId id dell'allerta
 * @param lon longitudine della posizione iniziale
 * @param lat latitudine della posizione iniziale
 * @param raisedAt istante di creazione dell'allerta
 */
public record SosAlertRaisedEvent(
        UUID alertId,
        double lon,
        double lat,
        Instant raisedAt
) {
    /**
     * Controlla che id e istante siano presenti.
     *
     * @throws NullPointerException se alertId o raisedAt sono null
     */
    public SosAlertRaisedEvent {
        Objects.requireNonNull(alertId, "alertId cannot be null");
        Objects.requireNonNull(raisedAt, "raisedAt cannot be null");
    }
}
