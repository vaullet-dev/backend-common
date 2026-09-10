package dev.vaullet.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What a service is allowed to vary about its security posture.
 *
 * <p>Bound to {@code vaullet.security}. The list is short by design — everything absent from it is a
 * platform decision (ADR-006, ADR-008) rather than a service preference, and making it configurable
 * would invite a deployment to opt out of it.
 *
 * @param publicPaths endpoints reachable without a token. Keep the list short and reviewed; the
 *     default covers the probes and the API documentation, and nothing else
 * @param actuatorRole role required for everything the Actuator exposes beyond health and info.
 *     {@code env} and {@code configprops} disclose configuration, so this is not decoration
 * @param rolesClaim JWT claim carrying realm roles, as a dotted path. ADR-006 has Keycloak emit them
 *     under {@code realm_access.roles}; the value becomes {@code ROLE_*} authorities
 * @param local developer-machine behaviour, applied only under the {@code local} profile
 */
@ConfigurationProperties(prefix = "vaullet.security")
public record SecurityProperties(
        @DefaultValue({
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/v3/api-docs",
                    "/v3/api-docs/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**"
                })
                List<String> publicPaths,
        @DefaultValue("OPERATOR") String actuatorRole,
        @DefaultValue("realm_access.roles") String rolesClaim,
        @DefaultValue Local local) {

    /**
     * @param anonymousAuthorities authorities handed to the anonymous principal under the
     *     {@code local} profile. Method security stays switched on there, so these are what make a
     *     developer exercise the real {@code @PreAuthorize} rules rather than a build with
     *     authorisation compiled out. List the scopes the service's own rules ask for
     */
    public record Local(@DefaultValue List<String> anonymousAuthorities) {}
}
