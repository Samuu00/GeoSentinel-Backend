package it.unical.geosentinel.shared.domain.exception;

import java.util.UUID;

public class ResourceNotFoundException extends DomainException{
    public ResourceNotFoundException(String message) {
        super(message);
    }
    public ResourceNotFoundException(String resourceName, UUID resourceId) {
        super(String.format("Resource %s with id %s not found", resourceName, resourceId));
    }
}
