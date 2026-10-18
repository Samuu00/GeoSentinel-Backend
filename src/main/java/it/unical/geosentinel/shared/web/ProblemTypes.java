package it.unical.geosentinel.shared.web;

import java.net.URI;

public final class ProblemTypes {
    private static final String BASE_URL = "https://geosentinel.dev/problems/";

    public static final URI VALIDATION_ERROR = URI.create(BASE_URL + "validation-error");
    public static final URI NOT_FOUND = URI.create(BASE_URL + "not-found");
    public static final URI BUSINESS_RULE = URI.create(BASE_URL + "business-rule");
    public static final URI CAPACITY_EXCEEDED = URI.create(BASE_URL + "capacity-exceeded");
    public static final URI ALERT_ALREADY_TAKEN = URI.create(BASE_URL + "alert-already-taken");
    public static final URI CONCURRENT_MODIFICATION = URI.create(BASE_URL + "concurrent-modification");
    public static final URI RESOURCE_BUSY = URI.create(BASE_URL + "resource-busy");
    public static final URI CONFLICT = URI.create(BASE_URL + "conflict");
    public static final URI IDEMPOTENCY_KEY_REUSED = URI.create(BASE_URL + "idempotency-key-reused");
    public static final URI PRECONDITION_REQUIRED = URI.create(BASE_URL + "precondition-required");
    public static final URI MEDIA_UNSUPPORTED = URI.create(BASE_URL + "media-unsupported-type");
    public static final URI MEDIA_PAYLOAD_TOO_LARGE = URI.create(BASE_URL + "media-payload-too-large");
    public static final URI MEDIA_QUOTA_EXCEEDED = URI.create(BASE_URL + "media-quota-exceeded");
    public static final URI STORAGE_UNAVAILABLE = URI.create(BASE_URL + "storage-unavailable");
    public static final URI FORBIDDEN = URI.create(BASE_URL + "forbidden");
    public static final URI UNAUTHORIZED = URI.create(BASE_URL + "unauthorized");
    public static final URI RATE_LIMIT_EXCEEDED = URI.create(BASE_URL + "rate-limit-exceeded");
    public static final URI INTERNAL_ERROR = URI.create(BASE_URL + "internal-error");
    public static final URI ACCOUNT_MANAGED = URI.create(BASE_URL + "account-managed");
    public static final URI AGE_REQUIREMENT_NOT_MET = URI.create(BASE_URL + "age-requirement-not-met");
    public static final URI INVALID_RESET_CODE = URI.create(BASE_URL + "invalid-reset-code");
    public static final URI IDENTITY_PROVIDER_UNAVAILABLE = URI.create(BASE_URL + "identity-provider-unavailable");
    public static final URI BIRTH_DATE_REQUIRED = URI.create(BASE_URL + "birth-date-required");

    private ProblemTypes() {
    }

}
