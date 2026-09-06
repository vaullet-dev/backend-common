package io.vaullet.common.security;

import io.micrometer.tracing.Tracer;
import io.vaullet.common.error.CommonErrorType;
import io.vaullet.common.web.ApiExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * {@link ApiExceptionHandler} plus the one handler that needs Spring Security on the classpath.
 *
 * <p>A subclass rather than a second {@code @RestControllerAdvice}. Spring consults advices in order
 * and takes the first with a matching handler, and the parent has a catch-all
 * {@code @ExceptionHandler(Exception.class)} — so a separate advice would need an {@code @Order} to
 * beat it, and an ordering that is only ever exercised by a denied request is an ordering that gets
 * broken without anyone noticing. Extending removes the question.
 *
 * <p>Registered by {@link PlatformSecurityAutoConfiguration}, which runs before the web module's
 * auto-configuration so that its {@code @ConditionalOnMissingBean(ApiExceptionHandler.class)} sees
 * this one and backs off. A service that wants both this and its own handlers subclasses <em>this</em>
 * class.
 *
 * <p>Note what this covers and what it does not: method security throws
 * {@code AccessDeniedException} inside the call stack, which reaches here. A request rejected by the
 * filter chain never enters a controller, and is answered by Spring Security's own
 * {@code AccessDeniedHandler} instead.
 */
@RestControllerAdvice
public class SecurityApiExceptionHandler extends ApiExceptionHandler {

    public SecurityApiExceptionHandler(ObjectProvider<Tracer> tracer) {
        super(tracer);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        // Deliberately vague: telling a caller *why* they were denied is an information leak — it
        // confirms the resource exists and hints at which authority would have worked.
        return problem(CommonErrorType.ACCESS_DENIED, "You do not have permission to perform this action", request);
    }
}
