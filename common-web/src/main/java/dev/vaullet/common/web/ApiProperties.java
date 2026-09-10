package dev.vaullet.common.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Inbound HTTP behaviour that differs per deployment rather than per service.
 *
 * <p>Bound to {@code vaullet.api}. The prefix is the organisation, not {@code app}, and that is
 * deliberate: {@code app} stays free for the settings a service genuinely owns, so a reader of an
 * {@code application.yaml} can tell at a glance which keys come from the shared library and which
 * are this codebase's own.
 *
 * <p>Everything here has a working default, so a service that adds this module and configures
 * nothing still starts, with CORS off.
 *
 * @param allowedOrigins browser origins allowed to call this API; empty disables CORS entirely,
 *     which is the right default for a service whose callers are other services (ADR-008)
 * @param corsPathPattern the path CORS applies to. Defaults to {@code /v1/**} because ADR-011, §2
 *     puts the major version in the URI; a service that has not adopted that yet overrides it
 * @param allowedMethods HTTP methods a browser may use cross-origin
 * @param exposedHeaders response headers a browser may read cross-origin. {@code Location} is here
 *     because a {@code 201} that a client cannot read the {@code Location} of is not much of a 201
 * @param maxAgeSeconds how long a browser may cache the pre-flight response
 */
@ConfigurationProperties(prefix = "vaullet.api")
public record ApiProperties(
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue("/v1/**") String corsPathPattern,
        @DefaultValue({"GET", "POST", "PUT", "PATCH", "DELETE"}) List<String> allowedMethods,
        @DefaultValue({"Location"}) List<String> exposedHeaders,
        @DefaultValue("3600") long maxAgeSeconds) {}
