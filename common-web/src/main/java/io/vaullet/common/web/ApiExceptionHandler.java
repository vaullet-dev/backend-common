package io.vaullet.common.web;

import io.micrometer.tracing.Tracer;
import io.vaullet.common.error.ApplicationException;
import io.vaullet.common.error.CommonErrorType;
import io.vaullet.common.error.ErrorType;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place where exceptions become HTTP responses, in RFC 9457 {@code problem+json}.
 *
 * <p>Design notes, which are the reason this is shared rather than copied:
 *
 * <ul>
 *   <li><b>Extend {@link ResponseEntityExceptionHandler}.</b> It already handles the ~15 Spring MVC
 *       exceptions (unreadable body, unsupported media type, missing header, …) as
 *       {@code ProblemDetail}. Writing an {@code @ExceptionHandler} for each re-invents a wheel that
 *       ships in the box.
 *   <li><b>One handler per <em>domain</em> failure, not per call site.</b> Controllers contain no
 *       try/catch and no {@code ResponseEntity.status(...)} plumbing. The single
 *       {@link ApplicationException} handler is what lets a service add an error without touching
 *       this class or this library.
 *   <li><b>Never leak internals.</b> Unexpected exceptions are logged with the stack trace and the
 *       correlation id; the client gets a generic body plus that id. Echoing {@code getMessage()} is
 *       how table names and SQL end up in a bug tracker.
 *   <li><b>The body shape is ADR-011, §7.</b> Alongside RFC 9457's {@code type}/{@code title}/
 *       {@code status}/{@code detail}/{@code instance} it carries {@code code} (the stable contract
 *       a client branches on) and {@code trace_id} (ADR-007's {@code correlation_id}).
 * </ul>
 *
 * <h2>Extending it</h2>
 *
 * <p>Subclass, annotate the subclass {@code @RestControllerAdvice}, and
 * {@code WebAutoConfiguration} backs off — it registers this one only when no
 * {@code ApiExceptionHandler} bean exists. Add {@code @ExceptionHandler} methods for exceptions this
 * library cannot know about, and override {@link #lockContentionErrorType()} when the service has a
 * better name for the contended thing.
 *
 * <p>Subclassing rather than a second advice is the recommended extension, and
 * {@code common-security} follows it: its {@code SecurityApiExceptionHandler} adds the
 * handler for Spring Security's {@code AccessDeniedException}, which cannot live here because this
 * module does not depend on Spring Security. Two advices work — the {@code @Order} on the more
 * specific one has to be right, because the catch-all below will otherwise answer first — but one
 * advice needs no ordering to be correct.
 *
 * <p>What a subclass must not do is produce an error body by hand. Use
 * {@link #problem(ErrorType, String, WebRequest)}, or {@link ProblemDetails} directly from a
 * separate advice, so every error out of the service has the same keys.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Optional: tracing is not auto-configured in every test slice. */
    private final ObjectProvider<Tracer> tracer;

    public ApiExceptionHandler(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /**
     * The error type for a row lock that could not be taken inside {@code statement_timeout}.
     *
     * <p>Override when the service has a more specific name for the contended resource. The ledger
     * returns {@code ACCOUNT_BUSY}, because there the contended row is always an account and that
     * code is already published in its API contract.
     */
    protected ErrorType lockContentionErrorType() {
        return CommonErrorType.RESOURCE_BUSY;
    }

    /**
     * Every failure the service raises on purpose.
     *
     * <p>Logged at debug, not warn. A refused request is frequently the design working — ADR-004
     * treats {@code INSUFFICIENT_FUNDS} as a business signal with a permanently non-zero rate — and
     * logging those at warn trains everyone to ignore warnings.
     */
    @ExceptionHandler(ApplicationException.class)
    public ProblemDetail handleApplicationException(ApplicationException ex, WebRequest request) {
        log.debug("Handled application error [{}]: {}", ex.errorType().code(), ex.getMessage());
        return problem(ex.errorType(), ex.getMessage(), request);
    }

    /**
     * A contended row, not a broken request: the same call a moment later will very likely work, so
     * it gets a 503 and a {@code Retry-After} rather than a 500. Returning {@code ResponseEntity}
     * instead of a bare {@code ProblemDetail} is what makes room for that header.
     */
    @ExceptionHandler({QueryTimeoutException.class, CannotAcquireLockException.class})
    public ResponseEntity<ProblemDetail> handleLockContention(Exception ex, WebRequest request) {
        log.warn("Lock contention or statement timeout", ex);
        var problem = problem(
                lockContentionErrorType(), "The resource is busy settling another operation. Retry shortly.", request);
        return ResponseEntity.status(problem.getStatus())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    /** {@code @Validated} on method parameters, as opposed to {@code @Valid} on a body. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        var problem = problem(CommonErrorType.VALIDATION_FAILED, "One or more parameters are invalid", request);
        problem.setProperty(
                "errors",
                ex.getConstraintViolations().stream()
                        .map(violation -> new FieldError(
                                violation.getPropertyPath().toString(), violation.getMessage()))
                        .sorted(Comparator.comparing(FieldError::field))
                        .toList());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return problem(CommonErrorType.INTERNAL_ERROR, "An unexpected error occurred", request);
    }

    /**
     * Enriches Spring's own body-validation problem with a structured {@code errors} array.
     *
     * <p>Clients need field-level detail to highlight the offending input; a flat sentence forces
     * them to parse prose.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        var problem = problem(CommonErrorType.VALIDATION_FAILED, "One or more fields are invalid", request);
        problem.setProperty(
                "errors",
                ex.getBindingResult().getFieldErrors().stream()
                        .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                        .sorted(Comparator.comparing(FieldError::field))
                        .toList());
        return ResponseEntity.status(problem.getStatus()).headers(headers).body(problem);
    }

    /** The one way to build a body. See the class Javadoc. */
    protected ProblemDetail problem(ErrorType errorType, String detail, WebRequest request) {
        return ProblemDetails.create(errorType, detail, request, tracer);
    }

    /** Field-level validation failure, serialised into the problem's {@code errors} array. */
    public record FieldError(String field, @Nullable String message) {}
}
