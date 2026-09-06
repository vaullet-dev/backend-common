package io.vaullet.common.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.1 metadata and the bearer scheme, generated from the controllers rather than
 * hand-maintained.
 *
 * <p>springdoc reads the actual request mappings and Bean Validation constraints, so the published
 * contract cannot drift from the code the way a checked-in {@code openapi.yaml} does. ADR-011, §5
 * takes this further: the generated document is committed as a build artefact and diffed in CI with
 * {@code oasdiff}, so a pull request that breaks the contract fails rather than merges.
 *
 * <p>Declaring the bearer scheme centrally means "Authorize" in Swagger UI actually works in every
 * service, which is the difference between docs people use and docs people skim. Every Vaullet
 * service validates the same Keycloak-issued token (ADR-006), so this is one description, not
 * fourteen.
 *
 * <p>What this deliberately does <em>not</em> do is declare {@code GroupedOpenApi} beans. ADR-011,
 * §5 requires one fragment per sellable module, selected by controller package, and the package
 * boundary is load-bearing — a controller in the wrong group ships an endpoint to a customer who did
 * not buy it. That mapping is knowledge only the service has.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    @ConditionalOnMissingBean
    OpenAPI apiDefinition(OpenApiProperties properties, @Value("${spring.application.name:application}") String name) {
        var info = new Info()
                .title(properties.title() != null ? properties.title() : name + " API")
                .version(properties.version())
                .contact(new Contact().name(properties.contactName()).email(properties.contactEmail()));

        if (properties.description() != null) {
            info.description(properties.description());
        }

        return new OpenAPI()
                .info(info)
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("OAuth2 access token issued by the platform identity provider.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
