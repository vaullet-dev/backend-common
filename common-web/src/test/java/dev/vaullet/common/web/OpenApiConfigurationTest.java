package dev.vaullet.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverterContextImpl;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * The two ways Swagger UI's "Authorize" can get a token: pasted in, or signed in through Keycloak.
 *
 * <p>The sign-in is derived, not configured: from the issuer every service already validates tokens
 * against. These pin the derivation, and that nothing appears where there is nowhere to sign in.
 */
class OpenApiConfigurationTest {

    private static final String ISSUER = "https://vaullet.dev/auth/realms/vaullet";

    private final OpenApiProperties info = new OpenApiProperties("Test API", "1", null, "Platform team", "platform@vaullet.dev");
    private final OpenApiOAuth2Properties defaults = new OpenApiOAuth2Properties(true, null, null);

    private OpenAPI document(OpenApiOAuth2Properties oauth2, String issuer) {
        return new OpenApiConfiguration().apiDefinition(info, oauth2, "test-service", issuer);
    }

    @Test
    @DisplayName("signs in through Keycloak's endpoints under the issuer, next to the pasted bearer token")
    void keycloak_under_the_issuer() {
        OpenAPI api = document(defaults, ISSUER);

        SecurityScheme keycloak = api.getComponents().getSecuritySchemes().get(OpenApiConfiguration.KEYCLOAK_SCHEME);
        assertThat(keycloak.getType()).isEqualTo(SecurityScheme.Type.OAUTH2);
        OAuthFlow flow = keycloak.getFlows().getAuthorizationCode();
        assertThat(flow.getAuthorizationUrl()).isEqualTo(ISSUER + "/protocol/openid-connect/auth");
        assertThat(flow.getTokenUrl()).isEqualTo(ISSUER + "/protocol/openid-connect/token");
        assertThat(flow.getScopes()).containsOnlyKeys("openid");
        assertThat(keycloak.getFlows().getImplicit()).isNull();
        assertThat(keycloak.getFlows().getPassword()).isNull();

        assertThat(api.getComponents().getSecuritySchemes().get(OpenApiConfiguration.BEARER_SCHEME).getScheme())
                .isEqualTo("bearer");
    }

    @Test
    @DisplayName("either scheme authorizes a request: two requirement objects, not one with both")
    void either_scheme() {
        OpenAPI api = document(defaults, ISSUER);

        assertThat(api.getSecurity())
                .extracting(SecurityRequirement::keySet)
                .containsExactly(
                        Set.of(OpenApiConfiguration.BEARER_SCHEME),
                        Set.of(OpenApiConfiguration.KEYCLOAK_SCHEME));
    }

    @Test
    @DisplayName("a trailing slash on the issuer does not double up in the URLs")
    void trailing_slash() {
        OAuthFlow flow = OpenApiConfiguration.signIn(defaults, ISSUER + "/").orElseThrow().getFlows().getAuthorizationCode();

        assertThat(flow.getAuthorizationUrl()).isEqualTo(ISSUER + "/protocol/openid-connect/auth");
    }

    @Test
    @DisplayName("no issuer, as under the local profile, means no sign-in: only the bearer scheme")
    void no_issuer() {
        OpenAPI api = document(defaults, "");

        assertThat(api.getComponents().getSecuritySchemes()).containsOnlyKeys(OpenApiConfiguration.BEARER_SCHEME);
        assertThat(api.getSecurity()).hasSize(1);
    }

    @Test
    @DisplayName("switched off, there is no sign-in even with an issuer")
    void switched_off() {
        OpenAPI api = document(new OpenApiOAuth2Properties(false, null, null), ISSUER);

        assertThat(api.getComponents().getSecuritySchemes()).containsOnlyKeys(OpenApiConfiguration.BEARER_SCHEME);
    }

    @Test
    @DisplayName("configured URLs win over the ones under the issuer")
    void configured_urls() {
        var oauth2 = new OpenApiOAuth2Properties(true, "https://idp.example/authorize", "https://idp.example/token");

        OAuthFlow flow = OpenApiConfiguration.signIn(oauth2, ISSUER).orElseThrow().getFlows().getAuthorizationCode();

        assertThat(flow.getAuthorizationUrl()).isEqualTo("https://idp.example/authorize");
        assertThat(flow.getTokenUrl()).isEqualTo("https://idp.example/token");
    }

    @Test
    @DisplayName("the platform defaults preset Swagger UI's client, with PKCE")
    void platform_defaults() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("vaullet/platform-defaults.yaml"));
        Properties defaults = yaml.getObject();

        assertThat(defaults)
                .containsEntry("springdoc.swagger-ui.oauth.client-id", "swagger-ui")
                .containsEntry("springdoc.swagger-ui.oauth.use-pkce-with-authorization-code-grant", true)
                .containsEntry("springdoc.swagger-ui.oauth.scopes", "openid");
    }

    /** A body with multi-word fields, the shape every request and response here has. */
    static final class Sample {
        private final String externalRef;
        private final boolean emailVerified;

        Sample(String externalRef, boolean emailVerified) {
            this.externalRef = externalRef;
            this.emailVerified = emailVerified;
        }

        public String getExternalRef() {
            return externalRef;
        }

        public boolean isEmailVerified() {
            return emailVerified;
        }
    }

    private static Map<String, Schema> propertiesOf(String strategy) {
        var context = new ModelConverterContextImpl(new OpenApiConfiguration().modelResolver(strategy));
        context.resolve(new AnnotatedType(Sample.class));
        return context.getDefinedModels().get("Sample").getProperties();
    }

    @Test
    @DisplayName("the document names fields as the wire does: snake_case under the platform default")
    void snake_case_properties() {
        assertThat(propertiesOf("SNAKE_CASE")).containsOnlyKeys("external_ref", "email_verified");
    }

    @Test
    @DisplayName("the platform default is SNAKE_CASE, the value the document follows")
    void platform_default_naming() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("vaullet/platform-defaults.yaml"));

        assertThat(yaml.getObject()).containsEntry("spring.jackson.property-naming-strategy", "SNAKE_CASE");
        assertThat(OpenApiConfiguration.namingStrategy("SNAKE_CASE")).contains(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Test
    @DisplayName("a service that names no strategy is described with the Java names, as it speaks")
    void no_strategy() {
        assertThat(OpenApiConfiguration.namingStrategy("")).isEmpty();
        assertThat(propertiesOf("")).containsOnlyKeys("externalRef", "emailVerified");
    }

    @Test
    @DisplayName("a strategy is read in any case, and one Jackson 2 lacks fails loudly instead of drifting")
    void strategy_names() {
        assertThat(OpenApiConfiguration.namingStrategy(" kebab_case ")).contains(PropertyNamingStrategies.KEBAB_CASE);
        assertThatThrownBy(() -> OpenApiConfiguration.namingStrategy("com.example.Custom"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.example.Custom");
    }
}
