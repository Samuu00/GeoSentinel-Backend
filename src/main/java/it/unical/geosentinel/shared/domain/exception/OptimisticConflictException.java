package it.unical.geosentinel.shared.domain.exception;

import java.util.UUID;

public class OptimisticConflictException extends DomainException{
    public OptimisticConflictException(String message) {
        super(message);
    }
    public OptimisticConflictException(String resourceName, UUID resourceId, long excpectedVersion, long currentVersion) {
        super(String.format("Resource %s with id %s has been modified since last read. Expected version %d, current version %d",
                resourceName, resourceId, excpectedVersion, currentVersion));
    }
}
