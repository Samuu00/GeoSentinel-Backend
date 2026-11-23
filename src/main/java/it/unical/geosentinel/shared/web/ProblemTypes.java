// Catalogo degli identificativi degli errori restituiti dall'API. Ogni errore è un ProblemDetail
// (RFC 7807) il cui campo type è un URI del tipo https://geosentinel.dev/problems/<slug>. L'app mobile
// riconosce l'errore dallo slug, mai dal titolo o dal messaggio, quindi questi valori non vanno cambiati.
package it.unical.geosentinel.shared.web;

import java.net.URI;

/**
 * Costanti con gli URI {@code type} dei ProblemDetail, usate da {@link GlobalExceptionHandler} e dai
 * filtri di sicurezza. Classe di sole costanti, non istanziabile.
 */
public final class ProblemTypes {
    /** Prefisso comune a tutti gli URI degli errori. */
    private static final String BASE_URL = "https://geosentinel.dev/problems/";

    /** 400: il body o i parametri non superano la Bean Validation (dettaglio nel campo errors). */
    public static final URI VALIDATION_ERROR = URI.create(BASE_URL + "validation-error");
    /** 404: risorsa inesistente. */
    public static final URI NOT_FOUND = URI.create(BASE_URL + "not-found");
    /** 422: violazione generica di una regola di business. */
    public static final URI BUSINESS_RULE = URI.create(BASE_URL + "business-rule");
    /** 409: posti letto del rifugio insufficienti. */
    public static final URI CAPACITY_EXCEEDED = URI.create(BASE_URL + "capacity-exceeded");
    /** 409: allerta SOS già presa in carico da un altro operatore. */
    public static final URI ALERT_ALREADY_TAKEN = URI.create(BASE_URL + "alert-already-taken");
    /** 409: conflitto del lock ottimistico, la risorsa va riletta. */
    public static final URI CONCURRENT_MODIFICATION = URI.create(BASE_URL + "concurrent-modification");
    /** 409: lock pessimistico non ottenuto in tempo, si può ritentare dopo Retry-After. */
    public static final URI RESOURCE_BUSY = URI.create(BASE_URL + "resource-busy");
    /** 409: violazione di un vincolo di integrità del database. */
    public static final URI CONFLICT = URI.create(BASE_URL + "conflict");
    /** 422: Idempotency-Key già usata con un body diverso. */
    public static final URI IDEMPOTENCY_KEY_REUSED = URI.create(BASE_URL + "idempotency-key-reused");
    /** 428: manca un header di precondizione obbligatorio (Idempotency-Key o If-Match). */
    public static final URI PRECONDITION_REQUIRED = URI.create(BASE_URL + "precondition-required");
    /** 415: tipo di file non ammesso. */
    public static final URI MEDIA_UNSUPPORTED = URI.create(BASE_URL + "media-unsupported-type");
    /** 413: file troppo grande. */
    public static final URI MEDIA_PAYLOAD_TOO_LARGE = URI.create(BASE_URL + "media-payload-too-large");
    /** 422: quota di spazio dell'utente esaurita. */
    public static final URI MEDIA_QUOTA_EXCEEDED = URI.create(BASE_URL + "media-quota-exceeded");
    /** 503: object storage non raggiungibile. */
    public static final URI STORAGE_UNAVAILABLE = URI.create(BASE_URL + "storage-unavailable");
    /** 403: utente autenticato ma senza i permessi necessari. */
    public static final URI FORBIDDEN = URI.create(BASE_URL + "forbidden");
    /** 401: credenziali o token mancanti o non validi. */
    public static final URI UNAUTHORIZED = URI.create(BASE_URL + "unauthorized");
    /** 429: troppe richieste, con Retry-After. */
    public static final URI RATE_LIMIT_EXCEEDED = URI.create(BASE_URL + "rate-limit-exceeded");
    /** 500: errore inatteso. */
    public static final URI INTERNAL_ERROR = URI.create(BASE_URL + "internal-error");
    /** 409: account del personale, gestito dall'organizzazione. */
    public static final URI ACCOUNT_MANAGED = URI.create(BASE_URL + "account-managed");
    /** 422: età inferiore a quella richiesta per registrarsi. */
    public static final URI AGE_REQUIREMENT_NOT_MET = URI.create(BASE_URL + "age-requirement-not-met");
    /** 422: codice di reset della password non valido o scaduto. */
    public static final URI INVALID_RESET_CODE = URI.create(BASE_URL + "invalid-reset-code");
    /** 503: provider di login esterno (Google) non raggiungibile. */
    public static final URI IDENTITY_PROVIDER_UNAVAILABLE = URI.create(BASE_URL + "identity-provider-unavailable");
    /** 503: motore di calcolo dei percorsi non raggiungibile. */
    public static final URI ROUTING_UNAVAILABLE = URI.create(BASE_URL + "routing-unavailable");
    /** 422: primo accesso con Google senza data di nascita. */
    public static final URI BIRTH_DATE_REQUIRED = URI.create(BASE_URL + "birth-date-required");
    /** 401: serve il codice della verifica in due passaggi. */
    public static final URI MFA_REQUIRED = URI.create(BASE_URL + "mfa-required");
    /** 401 al login, 422 nelle rotte /mfa: codice della verifica in due passaggi non valido. */
    public static final URI INVALID_MFA_CODE = URI.create(BASE_URL + "invalid-mfa-code");
    /** 422: nessun percorso sui sentieri tra i due punti. */
    public static final URI NO_ROUTE = URI.create(BASE_URL + "no-route");
    /** 409: verifica in due passaggi già attiva. */
    public static final URI MFA_ALREADY_ENABLED = URI.create(BASE_URL + "mfa-already-enabled");

    /** Costruttore privato: la classe contiene solo costanti. */
    private ProblemTypes() {
    }

}
