package dev.vaullet.common.web;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Swagger UI's sign-in: the OAuth2 authorization-code scheme {@link OpenApiConfiguration} offers next
 * to the pasted bearer token.
 *
 * <p>Bound to {@code vaullet.openapi.oauth2}. Nothing needs setting in a Vaullet service: the
 * endpoints default to Keycloak's under the issuer the service already validates tokens against
 * ({@code spring.security.oauth2.resourceserver.jwt.issuer-uri}), because Keycloak is the platform's
 * only token issuer (ADR-006). No issuer, as under the {@code local} profile, means no sign-in.
 *
 * <p>A class of its own rather than more components on {@link OpenApiProperties}: adding a component
 * to a public record changes its constructor, and that would be a breaking change for a feature that
 * is purely additive.
 *
 * <p>The client Swagger UI signs in with, and PKCE, are springdoc's own settings
 * ({@code springdoc.swagger-ui.oauth.*}); {@code vaullet/platform-defaults.yaml} presets them.
 *
 * @param enabled false to offer only the pasted bearer token
 * @param authorizationUrl where the browser signs in. Defaults to
 *     {@code <issuer>/protocol/openid-connect/auth}; set it only for an issuer that is not Keycloak
 * @param tokenUrl where Swagger UI exchanges the code for a token. Defaults to
 *     {@code <issuer>/protocol/openid-connect/token}
 */
@ConfigurationProperties(prefix = "vaullet.openapi.oauth2")
public record OpenApiOAuth2Properties(
        @DefaultValue("true") boolean enabled, @Nullable String authorizationUrl, @Nullable String tokenUrl) {}
