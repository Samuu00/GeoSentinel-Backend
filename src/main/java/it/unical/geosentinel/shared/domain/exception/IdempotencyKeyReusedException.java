package it.unical.geosentinel.shared.domain.exception;

// Stessa Idempotency-Key con un body diverso: non è un retry, è una chiave riusata dal client (ADR-0012, 422)
public class IdempotencyKeyReusedException extends DomainException {
    public IdempotencyKeyReusedException() {
        super("This Idempotency-Key was already used for a different request");
    }
}
