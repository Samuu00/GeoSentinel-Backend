package it.unical.geosentinel.shared.domain.exception;

import java.util.UUID;

public class CapacityExceededException extends DomainException {
    public CapacityExceededException(UUID refugeId, int requested, int available) {
        super("Cannot book %d beds for refuge '%s'. Only %d beds available in the selected range"
                .formatted(requested, refugeId, Math.max(0, available)));
    }

    private CapacityExceededException(String message) {
        super(message);
    }

    // updateCapacity: la nuova capacità non può scendere sotto gli ospiti già confermati
    public static CapacityExceededException belowConfirmedBookings(UUID refugeId, int capacity, int confirmedGuests) {
        return new CapacityExceededException("Cannot set capacity of refuge '%s' to %d: %d guests are already confirmed for one night"
                .formatted(refugeId, capacity, confirmedGuests));
    }
}
