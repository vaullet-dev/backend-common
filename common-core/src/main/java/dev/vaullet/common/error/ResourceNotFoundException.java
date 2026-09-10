package dev.vaullet.common.error;

import java.io.Serial;

/** Thrown when a resource addressed by the caller does not exist. Maps to 404. */
public class ResourceNotFoundException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String resource, Object identifier) {
        super(CommonErrorType.RESOURCE_NOT_FOUND, "%s '%s' was not found".formatted(resource, identifier));
    }

    /**
     * For the case where naming the identifier would tell a caller probing for valid ids more than
     * it tells a legitimate one.
     */
    public ResourceNotFoundException(String message) {
        super(CommonErrorType.RESOURCE_NOT_FOUND, message);
    }
}
