package dev.vaullet.common.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springdoc.core.properties.SpringDocConfigProperties;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

/**
 * OpenAPI 3.1 metadata and the security schemes, generated from the controllers rather than
 * hand-maintained.
 *
 * <p>springdoc reads the actual request mappings and Bean Validation constraints, so the published
 * contract cannot drift from the code the way a checked-in {@code openapi.yaml} does. ADR-011, §5
 * takes this further: the generated document is committed as a build artefact and diffed in CI with
 * {@code oasdiff}, so a pull request that breaks the contract fails rather than merges.
 *
 * <p>Declaring the schemes centrally means "Authorize" in Swagger UI actually works in every
 * service, which is the difference between docs people use and docs people skim. Every Vaullet
 * service validates the same Keycloak-issued token (ADR-006), so this is one description, not
 * fourteen. There are two, and either one authorizes a request:
 *
 * <ul>
 *   <li><b>{@code bearer-jwt}</b>: a token pasted in, from wherever the caller got it.
 *   <li><b>{@code keycloak}</b>: Swagger UI signs in itself, with the authorization-code flow and
 *       PKCE, through the client springdoc is told about ({@code springdoc.swagger-ui.oauth.*}, preset
 *       in {@code vaullet/platform-defaults.yaml}). Its endpoints come from the issuer the service
 *       already validates tokens against, so a service configures nothing; without an issuer, as
 *       under the {@code local} profile where no token is needed, the scheme is left out. See
 *       {@link OpenApiOAuth2Properties}.
 * </ul>
 *
 * <p>What this deliberately does <em>not</em> do is declare {@code GroupedOpenApi} beans. ADR-011,
 * §5 requires one fragment per sellable module, selected by controller package, and the package
 * boundary is load-bearing — a controller in the wrong group ships an endpoint to a customer who did
 * not buy it. That mapping is knowledge only the service has.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenApiConfiguration.class);

    static final String BEARER_SCHEME = "bearer-jwt";
    static final String KEYCLOAK_SCHEME = "keycloak";

    @Bean
    @ConditionalOnMissingBean
    OpenAPI apiDefinition(
            OpenApiProperties properties,
            OpenApiOAuth2Properties oauth2,
            @Value("${spring.application.name:application}") String name,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        var info = new Info()
                .title(properties.title() != null ? properties.title() : name + " API")
                .version(properties.version())
                .contact(new Contact().name(properties.contactName()).email(properties.contactEmail()));

        if (properties.description() != null) {
            info.description(properties.description());
        }

        var components = new Components()
                .addSecuritySchemes(
                        BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("OAuth2 access token issued by the platform identity provider."));

        var api = new OpenAPI()
                .info(info)
                .components(components)
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));

        // A second requirement object, not a second entry in the first: OpenAPI reads the list as
        // alternatives, so a request is authorized by either scheme rather than needing both.
        signIn(oauth2, issuerUri).ifPresent(scheme -> {
            components.addSecuritySchemes(KEYCLOAK_SCHEME, scheme);
            api.addSecurityItem(new SecurityRequirement().addList(KEYCLOAK_SCHEME));
        });

        return api;
    }

    /**
     * The error body every 4xx and 5xx refers to. A customizer rather than part of
     * {@link #apiDefinition}, so a service that brings its own {@code OpenAPI} bean still gets it, and
     * global, so it reaches every {@code GroupedOpenApi} fragment too. See {@link OpenApiResponses}.
     */
    @Bean
    GlobalOpenApiCustomizer problemSchemas() {
        return OpenApiResponses::addProblemSchemas;
    }

    /** Errors as problem+json, and a success response where an operation declares none. */
    @Bean
    GlobalOperationCustomizer problemResponses() {
        return (operation, handler) -> OpenApiResponses.describe(operation, OpenApiResponses.successStatus(handler));
    }

    /**
     * The document's property names in the wire format's naming strategy: {@code external_ref}, not
     * {@code externalRef}.
     *
     * <p>swagger-core builds every schema with its own Jackson 2 mapper ({@link Json#mapper()}), and
     * {@code spring.jackson.*} never reaches it, because Spring Boot 4 configures Jackson 3. Without this
     * the document shows the Java names for bodies the service reads and writes only in snake_case
     * (ADR-011, §8), and a client that follows the document has every multi-word field ignored. The
     * strategy is read from the same property, so a service that overrides it is described as it speaks.
     *
     * <p>It is built for the OpenAPI version springdoc writes, 3.1 unless a service sets
     * {@code springdoc.api-docs.version}, the way swagger-core builds its own: {@link Json31#mapper()} and
     * {@code openapi31}. A 3.0 resolver inside a 3.1 document leaves every object schema without its
     * {@code type}, drops the description next to a {@code $ref}, writes the money example {@code 60.00}
     * as {@code 60.0} and gives an array's items the array's description and example.
     *
     * <p>Ordered first so that it runs last. springdoc puts each {@code ModelConverter} bean at the
     * front of swagger-core's chain, in bean order, and this resolver ends the chain: it describes every
     * type itself and never hands one on. Unordered, it could land ahead of a converter the service
     * declares, and that converter would be skipped without a word.
     */
    @Bean
    @ConditionalOnMissingBean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ModelResolver modelResolver(
            @Value("${spring.jackson.property-naming-strategy:}") String strategy,
            ObjectProvider<SpringDocConfigProperties> springDoc) {
        SpringDocConfigProperties properties = springDoc.getIfAvailable();
        return resolver(strategy, properties == null || properties.isOpenapi31());
    }

    /** The resolver for a naming strategy and an OpenAPI version. See {@link #modelResolver}. */
    static ModelResolver resolver(String strategy, boolean openapi31) {
        var mapper = (openapi31 ? Json31.mapper() : Json.mapper()).copy();
        namingStrategy(strategy).ifPresent(mapper::setPropertyNamingStrategy);
        return new ModelResolver(mapper).openapi31(openapi31);
    }

    /**
     * Jackson 2's strategy for the name Spring Boot takes ({@code SNAKE_CASE}, any case), or nothing
     * when none is set.
     *
     * <p>Spring Boot also takes the class name of a strategy of the service's own. That is a Jackson 3
     * class, which swagger-core's Jackson 2 mapper cannot use, so such a name is logged and the document
     * keeps the Java names, as {@link ApiExceptionHandler} keeps them in validation errors. Failing
     * startup over the Swagger UI would take down a service that speaks correctly.
     */
    static Optional<PropertyNamingStrategy> namingStrategy(@Nullable String name) {
        if (!StringUtils.hasText(name)) {
            return Optional.empty();
        }
        try {
            return Optional.of((PropertyNamingStrategy) PropertyNamingStrategies.class
                    .getField(name.strip().toUpperCase(Locale.ROOT))
                    .get(null));
        } catch (ReflectiveOperationException | ClassCastException e) {
            log.warn("spring.jackson.property-naming-strategy={} is not a PropertyNamingStrategies constant; "
                    + "the OpenAPI document names fields as Java does", name);
            return Optional.empty();
        }
    }

    /**
     * The authorization-code scheme, or nothing when it is switched off or there is nowhere to sign
     * in.
     */
    static Optional<SecurityScheme> signIn(OpenApiOAuth2Properties properties, @Nullable String issuerUri) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        String issuer = issuerUri == null ? "" : StringUtils.trimTrailingCharacter(issuerUri.strip(), '/');
        String authorizationUrl = orUnderIssuer(properties.authorizationUrl(), issuer, "/protocol/openid-connect/auth");
        String tokenUrl = orUnderIssuer(properties.tokenUrl(), issuer, "/protocol/openid-connect/token");
        if (authorizationUrl == null || tokenUrl == null) {
            return Optional.empty();
        }
        return Optional.of(new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("Sign in through Keycloak: authorization code with PKCE. "
                        + "The token is the same one bearer-jwt takes, with the roles of whoever signed in.")
                .flows(new OAuthFlows()
                        .authorizationCode(new OAuthFlow()
                                .authorizationUrl(authorizationUrl)
                                .tokenUrl(tokenUrl)
                                .scopes(new Scopes().addString("openid", "Sign in with OpenID Connect")))));
    }

    private static @Nullable String orUnderIssuer(@Nullable String configured, String issuer, String path) {
        if (StringUtils.hasText(configured)) {
            return configured.strip();
        }
        return issuer.isEmpty() ? null : issuer + path;
    }
}
