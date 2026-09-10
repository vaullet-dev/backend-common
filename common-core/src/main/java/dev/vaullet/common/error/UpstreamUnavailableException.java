package dev.vaullet.common.error;

import java.io.Serial;

/** Thrown when a downstream dependency failed after retries were exhausted. Maps to 503. */
public class UpstreamUnavailableException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UpstreamUnavailableException(String upstream, Throwable cause) {
        super(CommonErrorType.UPSTREAM_UNAVAILABLE, "Upstream '%s' is unavailable".formatted(upstream), cause);
    }
}
