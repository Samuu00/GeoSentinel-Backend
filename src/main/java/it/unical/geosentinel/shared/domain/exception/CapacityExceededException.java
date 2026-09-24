package it.unical.geosentinel.shared.domain.exception;

import java.util.UUID;

public class CapacityExceededException extends DomainException {
    public CapacityExceededException(UUID refugeId, int requested, int available) {
        super("Cannot book %d beds for refuge '%s'. Only %d beds available in the selected range"
                .formatted(requested, refugeId, Math.max(0, available)));
    }
}