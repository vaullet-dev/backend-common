package dev.vaullet.common.error;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The failures every HTTP service has, regardless of what it does.
 *
 * <p>The test for membership here is not "does this sound platform-wide" but "would a service that
 * knows nothing about money still raise it". Validation fails everywhere; a row is missing
 * everywhere; something unexpected throws everywhere. {@code INSUFFICIENT_FUNDS} does not, however
 * central it is to Vaullet — it lives in the ledger's own catalogue until a second service needs it.
 * See {@link ErrorType} for why that boundary is drawn where it is.
 *
 * <p>Every entry is reachable from {@code common-web}'s exception handler without a service
 * writing anything, which is the other half of the test: a code in here that nothing in this library
 * can raise is a code that belongs in a service.
 */
public enum CommonErrorType implements ErrorType {

    /** The request failed bean validation before any business rule ran. */
    VALIDATION_FAILED("validation-failed", HttpStatus.BAD_REQUEST, "Request validation failed"),

    /** The resource the caller addressed does not exist. */
    RESOURCE_NOT_FOUND("resource-not-found", HttpStatus.NOT_FOUND, "Resource not found"),

    /** The request would violate a uniqueness or state invariant. */
    RESOURCE_CONFLICT("resource-conflict", HttpStatus.CONFLICT, "Resource conflict"),

    /**
     * A row lock could not be taken inside {@code statement_timeout}.
     *
     * <p>Contention on a hot row, not a failure of the request itself, so it is a 503 with
     * {@code Retry-After} rather than a 500: the same request a moment later is very likely to
     * succeed. A service with a more specific name for the contended thing overrides
     * {@code ApiExceptionHandler#lockContentionErrorType()} — the ledger calls this
     * {@code ACCOUNT_BUSY}, because on that service the contended row is always an account.
     */
    RESOURCE_BUSY("resource-busy", HttpStatus.SERVICE_UNAVAILABLE, "Resource temporarily busy"),

    /** A downstream dependency failed after retries were exhausted. */
    UPSTREAM_UNAVAILABLE("upstream-unavailable", HttpStatus.SERVICE_UNAVAILABLE, "Upstream unavailable"),

    /** The principal is authenticated but not permitted to perform this operation. */
    ACCESS_DENIED("access-denied", HttpStatus.FORBIDDEN, "Access denied"),

    /** Anything unplanned. The detail is always generic; the trace id is how it gets diagnosed. */
    INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final URI type;
    private final HttpStatus status;
    private final String title;

    CommonErrorType(String slug, HttpStatus status, String title) {
        this.type = ErrorType.documentationUri(slug);
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public URI type() {
        return type;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
