// Gestore centralizzato degli errori dell'API. Intercetta le eccezioni lanciate da controller e service
// e le trasforma in risposte ProblemDetail (RFC 7807) con uno status HTTP e uno slug stabili, che l'app
// mobile usa per riconoscere l'errore. Gestisce anche gli errori standard di Spring MVC (400, 405, 415 e
// simili) e aggiunge a ogni risposta instance, timestamp e traceId.
package it.unical.geosentinel.shared.web;

import it.unical.geosentinel.shared.domain.exception.*;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Advice globale che traduce le eccezioni in ProblemDetail. Estende
 * {@link ResponseEntityExceptionHandler} per ereditare la gestione delle eccezioni di Spring MVC e
 * personalizzarne alcune (validazione, header mancanti).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    /** Header di precondizione: se mancano la risposta è 428 invece del 400 di default di Spring. */
    private static final Set<String> PRECONDITION_HEADERS = Set.of("Idempotency-Key", HttpHeaders.IF_MATCH);

    /** Orologio usato per il campo timestamp delle risposte. */
    private final Clock clock;

    /**
     * @param clock orologio dell'applicazione
     */
    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /**
     * Punto unico in cui ogni risposta di errore viene arricchita: sia i ProblemDetail costruiti in questa
     * classe sia quelli prodotti da Spring MVC (400, 405, 415 e altri) ricevono instance, timestamp e traceId.
     * Tutti i metodi di questa classe passano da qui.
     *
     * @param ex         eccezione gestita
     * @param body       corpo della risposta, di solito un ProblemDetail
     * @param headers    header da aggiungere
     * @param statusCode status HTTP
     * @param request    richiesta corrente
     * @return la risposta, con il ProblemDetail arricchito
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problemDetail) {
            enrich(problemDetail, request);
        }
        return response;
    }

    // ---- Validazione dell'input -------------------------------------------------------------------------------------------

    /**
     * Errori di validazione del body ({@code @Valid}): raccoglie gli errori sui singoli campi e quelli
     * globali dell'oggetto (ad esempio i vincoli tra due campi) in un unico elenco.
     *
     * @return 400 {@code validation-error} con il campo errors
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = Stream.concat(
                ex.getBindingResult().getFieldErrors().stream()
                        .map(e -> fieldError(e.getField(), e.getDefaultMessage())),
                ex.getBindingResult().getGlobalErrors().stream()
                        .map(e -> fieldError(e.getObjectName(), e.getDefaultMessage()))
        ).toList();
        return validationProblem(ex, errors, headers, request);
    }

    /**
     * Errori di validazione sui parametri del metodo del controller, cioè i vincoli su
     * {@code @RequestParam} e {@code @PathVariable} (ad esempio {@code @Max} sul raggio di ricerca).
     *
     * @return 400 {@code validation-error} con il campo errors
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> fieldError(parameterName(result.getMethodParameter().getParameterName(), error),
                                error.getDefaultMessage())))
                .toList();
        return validationProblem(ex, errors, headers, request);
    }

    /**
     * Header obbligatori mancanti. Se manca un header di precondizione (Idempotency-Key o If-Match) la
     * risposta è 428 {@code precondition-required}, più precisa del 400 che Spring darebbe di default; negli
     * altri casi si delega al comportamento standard.
     *
     * @return 428 oppure la risposta di default di Spring
     */
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing
                && PRECONDITION_HEADERS.contains(missing.getHeaderName())) {
            return problem(ex, HttpStatus.PRECONDITION_REQUIRED, ProblemTypes.PRECONDITION_REQUIRED,
                    "Precondition required", "The " + missing.getHeaderName() + " header is required",
                    headers, request);
        }
        return super.handleServletRequestBindingException(ex, headers, status, request);
    }

    /**
     * Violazioni di vincoli rilevate fuori dal layer web, ad esempio su un bean annotato con
     * {@code @Validated}.
     *
     * @return 400 {@code validation-error} con il campo errors
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> fieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return validationProblem(ex, errors, new HttpHeaders(), request);
    }

    // ---- Eccezioni di dominio -----------------------------------------------------------------------------------------------

    /**
     * @return 404 {@code not-found}
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(ResourceNotFoundException ex, WebRequest request) {
        return problem(ex, HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Resource not found", ex.getMessage(),
                new HttpHeaders(), request);
    }

    /**
     * @return 422 {@code business-rule} con il messaggio dell'eccezione
     */
    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<Object> handleBusinessRuleViolation(BusinessRuleViolationException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.BUSINESS_RULE, "Business rule violation",
                ex.getMessage(), new HttpHeaders(), request);
    }

    /**
     * @return 409 {@code capacity-exceeded}
     */
    @ExceptionHandler(CapacityExceededException.class)
    public ResponseEntity<Object> handleCapacityExceeded(CapacityExceededException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.CAPACITY_EXCEEDED, "Capacity exceeded",
                ex.getMessage(), new HttpHeaders(), request);
    }

    /**
     * @return 422 {@code no-route}
     */
    @ExceptionHandler(NoRouteException.class)
    public ResponseEntity<Object> handleNoRoute(NoRouteException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.NO_ROUTE, "No route",
                ex.getMessage(), new HttpHeaders(), request);
    }

    /**
     * @return 409 {@code alert-already-taken}
     */
    @ExceptionHandler(AlertAlreadyTakenException.class)
    public ResponseEntity<Object> handleAlertAlreadyTaken(AlertAlreadyTakenException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.ALERT_ALREADY_TAKEN, "Alert already taken",
                ex.getMessage(), new HttpHeaders(), request);
    }

    /**
     * Sceglie status e slug in base al motivo del rifiuto del file.
     *
     * @return 415, 413 oppure 422 con lo slug corrispondente
     */
    @ExceptionHandler(MediaRejectedException.class)
    public ResponseEntity<Object> handleMediaRejected(MediaRejectedException ex, WebRequest request) {
        return switch (ex.getReason()) {
            case UNSUPPORTED_TYPE -> problem(ex, HttpStatus.UNSUPPORTED_MEDIA_TYPE, ProblemTypes.MEDIA_UNSUPPORTED,
                    "Unsupported media type", ex.getMessage(), new HttpHeaders(), request);
            case TOO_LARGE -> problem(ex, HttpStatus.PAYLOAD_TOO_LARGE, ProblemTypes.MEDIA_PAYLOAD_TOO_LARGE,
                    "Media too large", ex.getMessage(), new HttpHeaders(), request);
            case QUOTA_EXCEEDED -> problem(ex, HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.MEDIA_QUOTA_EXCEEDED,
                    "Storage quota exceeded", ex.getMessage(), new HttpHeaders(), request);
        };
    }

    /**
     * Lo storage dei file non risponde. Il detail è generico perché il messaggio originale di MinIO può
     * contenere host e nomi dei bucket interni.
     *
     * @return 503 {@code storage-unavailable} con Retry-After di 5 secondi
     */
    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<Object> handleStorageUnavailable(StorageUnavailableException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");
        return problem(ex, HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.STORAGE_UNAVAILABLE, "Storage unavailable",
                "The file storage is temporarily unavailable. Retry shortly.", headers, request);
    }

    /**
     * @return 503 {@code routing-unavailable} con Retry-After di 5 secondi e un detail generico
     */
    @ExceptionHandler(RoutingUnavailableException.class)
    public ResponseEntity<Object> handleRoutingUnavailable(RoutingUnavailableException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");
        return problem(ex, HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.ROUTING_UNAVAILABLE, "Routing unavailable",
                "The routing service is temporarily unavailable. Retry shortly.", headers, request);
    }

    /**
     * @return 503 {@code identity-provider-unavailable} con Retry-After di 5 secondi e un detail generico
     */
    @ExceptionHandler(IdentityProviderUnavailableException.class)
    public ResponseEntity<Object> handleIdentityProviderUnavailable(IdentityProviderUnavailableException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");
        return problem(ex, HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.IDENTITY_PROVIDER_UNAVAILABLE,
                "Identity provider unavailable",
                "The sign-in provider is temporarily unavailable. Retry shortly.", headers, request);
    }

    /**
     * @return 422 {@code idempotency-key-reused}
     */
    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<Object> handleIdempotencyKeyReused(IdempotencyKeyReusedException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.IDEMPOTENCY_KEY_REUSED, "Idempotency key reused",
                ex.getMessage(), new HttpHeaders(), request);
    }

    /**
     * Troppe richieste segnalate da un service. Oltre all'header Retry-After riporta l'attesa anche nella
     * proprietà retryAfterSeconds del body.
     *
     * @return 429 {@code rate-limit-exceeded}
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Object> handleRateLimitExceeded(RateLimitExceededException ex, WebRequest request) {
        // Arrotondamento per eccesso, come in RateLimitFilter: per difetto il client ritenterebbe troppo presto.
        long retryAfterSeconds = Math.max(1, Math.ceilDiv(ex.getRetryAfter().toMillis(), 1000));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Please retry after " + retryAfterSeconds + " seconds.");
        problemDetail.setType(ProblemTypes.RATE_LIMIT_EXCEEDED);
        problemDetail.setTitle("Too Many Requests");
        problemDetail.setProperty("retryAfterSeconds", retryAfterSeconds);
        return handleExceptionInternal(ex, problemDetail, headers, HttpStatus.TOO_MANY_REQUESTS, request);
    }

    // ---- Concorrenza e persistenza ----------------------------------------------------------------------------

    /**
     * Conflitto del lock ottimistico, sia quello rilevato dal confronto esplicito con If-Match sia quello
     * di Hibernate su {@code @Version} al flush.
     *
     * @return 409 {@code concurrent-modification}: il client deve rileggere la risorsa
     */
    @ExceptionHandler({OptimisticConflictException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Object> handleOptimisticConflict(Exception ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.CONCURRENT_MODIFICATION, "Concurrent modification",
                "The resource was modified by another request. Reload it and retry.", new HttpHeaders(), request);
    }

    /**
     * Lock pessimistico non ottenuto, compreso lo scadere del lock_timeout di PostgreSQL
     * (CannotAcquireLockException). La richiesta si può ripetere poco dopo.
     *
     * @return 409 {@code resource-busy} con Retry-After di 2 secondi
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Object> handlePessimisticLock(PessimisticLockingFailureException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "2");
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.RESOURCE_BUSY, "Resource busy",
                "The resource is locked by another request. Retry shortly.", headers, request);
    }

    /**
     * @return 409 {@code conflict} con un messaggio generico
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        // Il nome del vincolo violato resta nel log e non va mai nella risposta, perché rivela lo schema del database.
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Data conflict",
                "The operation violates a data integrity rule", new HttpHeaders(), request);
    }

    // ---- Sicurezza e fallback ----------------------------------------------------------------------------------

    /**
     * Credenziali o refresh token non validi rilevati dai service di login e refresh. Senza questo handler
     * finirebbero nel fallback e diventerebbero un 500.
     *
     * @return 401 {@code unauthorized} con l'header WWW-Authenticate
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return problem(ex, HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED, "Unauthorized",
                "Authentication failed", headers, request);
    }

    /**
     * Accesso negato per mancanza di permessi. I controlli di {@code @PreAuthorize} falliscono dentro la
     * chiamata al controller, quindi l'eccezione arriva qui e non all'entry point della catena di sicurezza.
     *
     * @return 403 {@code forbidden}
     */
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<Object> handleAccessDenied(Exception ex, WebRequest request) {
        return problem(ex, HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Forbidden",
                "Access is denied: insufficient privileges", new HttpHeaders(), request);
    }

    /**
     * Fallback per ogni eccezione non prevista: la registra nel log con lo stack trace e risponde con un
     * messaggio generico, senza dettagli interni.
     *
     * @return 500 {@code internal-error}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnhandled(Exception ex, WebRequest request) {
        log.error("Unhandled exception on {}", request.getDescription(false), ex);
        return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR, "Internal server error",
                "An unexpected internal error occurred", new HttpHeaders(), request);
    }

    // ---- Account e autenticazione --------------------------------------------------------------------------------------

    /**
     * @return 409 {@code account-managed}
     */
    @ExceptionHandler(AccountManagedException.class)
    public ResponseEntity<Object> handleAccountManaged(AccountManagedException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.ACCOUNT_MANAGED, "Account managed by the organization",
                ex.getMessage(), new HttpHeaders(), request);
    }

    /**
     * Età insufficiente per registrarsi. Il detail è generico e non rivela la soglia, così il controllo
     * dell'età resta neutro e non suggerisce quale data inserire.
     *
     * @return 422 {@code age-requirement-not-met}
     */
    @ExceptionHandler(AgeRequirementNotMetException.class)
    public ResponseEntity<Object> handleAgeRequirementNotMet(AgeRequirementNotMetException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.AGE_REQUIREMENT_NOT_MET, "Age requirement not met",
                "Registration is not available for this age", new HttpHeaders(), request);
    }

    /**
     * Primo accesso con Google senza data di nascita: l'app la chiede e ripete la richiesta con lo stesso
     * ID token.
     *
     * @return 422 {@code birth-date-required}
     */
    @ExceptionHandler(BirthDateRequiredException.class)
    public ResponseEntity<Object> handleBirthDateRequired(BirthDateRequiredException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.BIRTH_DATE_REQUIRED, "Birth date required",
                ex.getMessage(), new HttpHeaders(), request);
    }

    /**
     * Codice di reset non valido. Il messaggio è lo stesso per tutti i motivi (sbagliato, scaduto, già
     * usato, tentativi esauriti), così non si capisce quale si è verificato.
     *
     * @return 422 {@code invalid-reset-code}
     */
    @ExceptionHandler(InvalidResetCodeException.class)
    public ResponseEntity<Object> handleInvalidResetCode(InvalidResetCodeException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.INVALID_RESET_CODE, "Invalid reset code",
                "The reset code is invalid or expired", new HttpHeaders(), request);
    }

    /**
     * Password corretta ma codice della verifica in due passaggi assente: il client chiede il codice e
     * rimanda la stessa richiesta.
     *
     * @return 401 {@code mfa-required}
     */
    @ExceptionHandler(MfaRequiredException.class)
    public ResponseEntity<Object> handleMfaRequired(MfaRequiredException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNAUTHORIZED, ProblemTypes.MFA_REQUIRED, "Two-factor code required",
                ex.getMessage(), new HttpHeaders(), request);
    }

    /**
     * Codice della verifica in due passaggi errato durante il login. Il client non è ancora autenticato,
     * quindi lo status è 401. Spring sceglie questo handler invece di quello di InvalidMfaCodeException
     * perché la classe è più specifica.
     *
     * @return 401 {@code invalid-mfa-code}
     */
    @ExceptionHandler(InvalidLoginMfaCodeException.class)
    public ResponseEntity<Object> handleInvalidLoginMfaCode(InvalidLoginMfaCodeException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNAUTHORIZED, ProblemTypes.INVALID_MFA_CODE, "Invalid two-factor code",
                ex.getMessage(), new HttpHeaders(), request);
    }

    /**
     * Codice della verifica in due passaggi errato sulle rotte {@code /mfa}, dove l'utente è già
     * autenticato.
     *
     * @return 422 {@code invalid-mfa-code}
     */
    @ExceptionHandler(InvalidMfaCodeException.class)
    public ResponseEntity<Object> handleInvalidMfaCode(InvalidMfaCodeException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.INVALID_MFA_CODE, "Invalid two-factor code",
                ex.getMessage(), new HttpHeaders(), request);
    }

    /**
     * @return 409 {@code mfa-already-enabled}
     */
    @ExceptionHandler(MfaAlreadyEnabledException.class)
    public ResponseEntity<Object> handleMfaAlreadyEnabled(MfaAlreadyEnabledException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.MFA_ALREADY_ENABLED, "Two-factor already enabled",
                ex.getMessage(), new HttpHeaders(), request);
    }

    // ---- Metodi di supporto ----------------------------------------------------------------------------------------------

    /**
     * Costruisce un ProblemDetail con status, tipo, titolo e detail e lo passa a
     * {@link #handleExceptionInternal} per l'arricchimento.
     *
     * @param ex      eccezione gestita
     * @param status  status HTTP
     * @param type    URI dello slug, da {@link ProblemTypes}
     * @param title   titolo breve, in inglese
     * @param detail  descrizione per il client, in inglese
     * @param headers header da aggiungere (ad esempio Retry-After)
     * @param request richiesta corrente
     * @return la risposta completa
     */
    private ResponseEntity<Object> problem(Exception ex, HttpStatus status, URI type, String title, String detail,
                                           HttpHeaders headers, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setType(type);
        problemDetail.setTitle(title);
        return handleExceptionInternal(ex, problemDetail, headers, status, request);
    }

    /**
     * Costruisce la risposta 400 {@code validation-error} con l'elenco degli errori sui campi.
     *
     * @param ex      eccezione gestita
     * @param errors  coppie campo e messaggio
     * @param headers header da aggiungere
     * @param request richiesta corrente
     * @return la risposta completa
     */
    private ResponseEntity<Object> validationProblem(Exception ex, List<Map<String, String>> errors,
                                                     HttpHeaders headers, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problemDetail.setType(ProblemTypes.VALIDATION_ERROR);
        problemDetail.setTitle("Validation error");
        problemDetail.setProperty("errors", errors);
        return handleExceptionInternal(ex, problemDetail, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Aggiunge al ProblemDetail il percorso della richiesta (instance), l'istante corrente e, se
     * presente nell'MDC, il traceId con cui ritrovare la richiesta nei log.
     *
     * @param problemDetail errore da completare
     * @param request       richiesta corrente
     */
    private void enrich(ProblemDetail problemDetail, WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            problemDetail.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        problemDetail.setProperty("timestamp", Instant.now(clock));
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            problemDetail.setProperty("traceId", traceId);
        }
    }

    /**
     * Ricava il nome da mostrare per un parametro non valido.
     *
     * @param parameter nome del parametro del metodo
     * @param error     errore di validazione
     * @return il nome del parametro, eventualmente seguito dal campo interno
     */
    private static String parameterName(String parameter, MessageSourceResolvable error) {
        // Gli errori sugli elementi di un contenitore (ad esempio List<@NotNull ...>) indicano anche il campo
        // interno al parametro, che si aggiunge al nome con un punto.
        return error instanceof FieldError fieldError ? parameter + "." + fieldError.getField() : parameter;
    }

    /**
     * Crea una voce dell'elenco errors, sostituendo i valori nulli con stringhe di ripiego.
     *
     * @param field   nome del campo
     * @param message messaggio di errore
     * @return mappa con le chiavi field e message
     */
    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field == null ? "" : field, "message", message == null ? "Invalid value" : message);
    }
}
