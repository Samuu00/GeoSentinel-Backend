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

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Set<String> PRECONDITION_HEADERS = Set.of("Idempotency-Key", HttpHeaders.IF_MATCH);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    // Unico punto di arricchimento: ogni ProblemDetail (nostri e quelli di Spring MVC 400/405/415/...) riceve instance, timestamp, traceId.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problemDetail) {
            enrich(problemDetail, request);
        }
        return response;
    }

    // ---- validazione -------------------------------------------------------------------------------------------

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

    // vincoli su @RequestParam / @PathVariable (es. @Max su radius)
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

    // header di precondizione mancanti (Idempotency-Key, If-Match): 428, non il 400 di default di Spring
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

    // bean @Validated fuori dal layer web
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> fieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return validationProblem(ex, errors, new HttpHeaders(), request);
    }

    // ---- dominio -----------------------------------------------------------------------------------------------

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(ResourceNotFoundException ex, WebRequest request) {
        return problem(ex, HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Resource not found", ex.getMessage(),
                new HttpHeaders(), request);
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<Object> handleBusinessRuleViolation(BusinessRuleViolationException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.BUSINESS_RULE, "Business rule violation",
                ex.getMessage(), new HttpHeaders(), request);
    }

    @ExceptionHandler(CapacityExceededException.class)
    public ResponseEntity<Object> handleCapacityExceeded(CapacityExceededException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.CAPACITY_EXCEEDED, "Capacity exceeded",
                ex.getMessage(), new HttpHeaders(), request);
    }

    @ExceptionHandler(AlertAlreadyTakenException.class)
    public ResponseEntity<Object> handleAlertAlreadyTaken(AlertAlreadyTakenException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.ALERT_ALREADY_TAKEN, "Alert already taken",
                ex.getMessage(), new HttpHeaders(), request);
    }

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

    // Detail generico: il messaggio di MinIO può contenere host e bucket interni
    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<Object> handleStorageUnavailable(StorageUnavailableException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");
        return problem(ex, HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.STORAGE_UNAVAILABLE, "Storage unavailable",
                "The file storage is temporarily unavailable. Retry shortly.", headers, request);
    }

    @ExceptionHandler(IdentityProviderUnavailableException.class)
    public ResponseEntity<Object> handleIdentityProviderUnavailable(IdentityProviderUnavailableException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "5");
        return problem(ex, HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.IDENTITY_PROVIDER_UNAVAILABLE,
                "Identity provider unavailable",
                "The sign-in provider is temporarily unavailable. Retry shortly.", headers, request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<Object> handleIdempotencyKeyReused(IdempotencyKeyReusedException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.IDEMPOTENCY_KEY_REUSED, "Idempotency key reused",
                ex.getMessage(), new HttpHeaders(), request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Object> handleRateLimitExceeded(RateLimitExceededException ex, WebRequest request) {
        // arrotondato per eccesso, come in RateLimitFilter: per difetto il client ritenterebbe troppo presto
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

    // ---- concorrenza & persistenza ----------------------------------------------------------------------------

    @ExceptionHandler({OptimisticConflictException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Object> handleOptimisticConflict(Exception ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.CONCURRENT_MODIFICATION, "Concurrent modification",
                "The resource was modified by another request. Reload it and retry.", new HttpHeaders(), request);
    }

    // include il lock_timeout di Postgres (CannotAcquireLockException)
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Object> handlePessimisticLock(PessimisticLockingFailureException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "2");
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.RESOURCE_BUSY, "Resource busy",
                "The resource is locked by another request. Retry shortly.", headers, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        // i dettagli del constraint restano nel log, mai nella risposta
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Data conflict",
                "The operation violates a data integrity rule", new HttpHeaders(), request);
    }

    // ---- sicurezza & fallback ----------------------------------------------------------------------------------

    // credenziali o refresh token non validi lanciati dai service (login/refresh): 401, non il fallback 500
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return problem(ex, HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED, "Unauthorized",
                "Authentication failed", headers, request);
    }

    // i fallimenti di @PreAuthorize sono lanciati dentro la chiamata al controller, quindi arrivano qui e non al filtro di sicurezza
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<Object> handleAccessDenied(Exception ex, WebRequest request) {
        return problem(ex, HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Forbidden",
                "Access is denied: insufficient privileges", new HttpHeaders(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnhandled(Exception ex, WebRequest request) {
        log.error("Unhandled exception on {}", request.getDescription(false), ex);
        return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR, "Internal server error",
                "An unexpected internal error occurred", new HttpHeaders(), request);
    }

    // ---- account --------------------------------------------------------------------------------------

    @ExceptionHandler(AccountManagedException.class)
    public ResponseEntity<Object> handleAccountManaged(AccountManagedException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.ACCOUNT_MANAGED, "Account managed by the organization",
                ex.getMessage(), new HttpHeaders(), request);
    }

    // detail generico: non rivela la soglia (age gate neutro)
    @ExceptionHandler(AgeRequirementNotMetException.class)
    public ResponseEntity<Object> handleAgeRequirementNotMet(AgeRequirementNotMetException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.AGE_REQUIREMENT_NOT_MET, "Age requirement not met",
                "Registration is not available for this age", new HttpHeaders(), request);
    }

    // Un solo messaggio per tutti i motivi (sbagliato, scaduto, già usato, tentativi finiti): non si distingue quale
    @ExceptionHandler(InvalidResetCodeException.class)
    public ResponseEntity<Object> handleInvalidResetCode(InvalidResetCodeException ex, WebRequest request) {
        return problem(ex, HttpStatus.UNPROCESSABLE_ENTITY, ProblemTypes.INVALID_RESET_CODE, "Invalid reset code",
                "The reset code is invalid or expired", new HttpHeaders(), request);
    }

    // ---- metodi di supporto ----------------------------------------------------------------------------------------------

    private ResponseEntity<Object> problem(Exception ex, HttpStatus status, URI type, String title, String detail,
                                           HttpHeaders headers, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setType(type);
        problemDetail.setTitle(title);
        return handleExceptionInternal(ex, problemDetail, headers, status, request);
    }

    private ResponseEntity<Object> validationProblem(Exception ex, List<Map<String, String>> errors,
                                                     HttpHeaders headers, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problemDetail.setType(ProblemTypes.VALIDATION_ERROR);
        problemDetail.setTitle("Validation error");
        problemDetail.setProperty("errors", errors);
        return handleExceptionInternal(ex, problemDetail, headers, HttpStatus.BAD_REQUEST, request);
    }

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

    private static String parameterName(String parameter, MessageSourceResolvable error) {
        // gli errori sugli elementi di un container (es. List<@NotNull ...>) indicano il campo dentro il parametro
        return error instanceof FieldError fieldError ? parameter + "." + fieldError.getField() : parameter;
    }

    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field == null ? "" : field, "message", message == null ? "Invalid value" : message);
    }
}
