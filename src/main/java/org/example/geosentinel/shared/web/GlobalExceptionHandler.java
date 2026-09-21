package org.example.geosentinel.shared.web;

import jakarta.validation.ConstraintViolationException;
import org.example.geosentinel.shared.domain.exception.BusinessRuleViolationException;
import org.example.geosentinel.shared.domain.exception.OptimisticConflictException;
import org.example.geosentinel.shared.domain.exception.ResourceNotFoundException;
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
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
import java.util.stream.Stream;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    // Single enrichment point: every ProblemDetail (ours and Spring MVC's 400/405/415/...) gets instance, timestamp, traceId.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problemDetail) {
            enrich(problemDetail, request);
        }
        return response;
    }

    // ---- validation -------------------------------------------------------------------------------------------

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

    // @RequestParam / @PathVariable constraints (e.g. @Max on radius)
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

    // @Validated beans outside the web layer
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> fieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return validationProblem(ex, errors, new HttpHeaders(), request);
    }

    // ---- domain -----------------------------------------------------------------------------------------------

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

    // ---- concurrency & persistence ----------------------------------------------------------------------------

    @ExceptionHandler({OptimisticConflictException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Object> handleOptimisticConflict(Exception ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.CONCURRENT_MODIFICATION, "Concurrent modification",
                "The resource was modified by another request. Reload it and retry.", new HttpHeaders(), request);
    }

    // includes Postgres lock_timeout (CannotAcquireLockException)
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Object> handlePessimisticLock(PessimisticLockingFailureException ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "2");
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.RESOURCE_BUSY, "Resource busy",
                "The resource is locked by another request. Retry shortly.", headers, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        // constraint details stay in the log, never in the response
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(ex, HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Data conflict",
                "The operation violates a data integrity rule", new HttpHeaders(), request);
    }

    // ---- security & fallback ----------------------------------------------------------------------------------

    // @PreAuthorize failures are thrown inside the controller call, so they land here instead of the security filter
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

    // ---- helpers ----------------------------------------------------------------------------------------------

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
        // container element errors (e.g. List<@NotNull ...>) report the field inside the parameter
        return error instanceof FieldError fieldError ? parameter + "." + fieldError.getField() : parameter;
    }

    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field == null ? "" : field, "message", message == null ? "Invalid value" : message);
    }
}
