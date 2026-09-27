package it.unical.geosentinel.shared.domain.exception;

import java.util.UUID;

// 409: un altro operatore ha già inviato una squadra su questa allerta. Qui perché l'handler è in shared
public class AlertAlreadyTakenException extends DomainException {
    public AlertAlreadyTakenException(UUID alertId) {
        super("SOS alert '%s' has already been taken by another operator".formatted(alertId));
    }
}
