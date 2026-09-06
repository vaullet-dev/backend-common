package io.vaullet.common.error;

import java.io.Serial;

/** Thrown when a request would violate a uniqueness or state invariant. Maps to 409. */
public class ResourceConflictException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ResourceConflictException(String message) {
        super(CommonErrorType.RESOURCE_CONFLICT, message);
    }
}
