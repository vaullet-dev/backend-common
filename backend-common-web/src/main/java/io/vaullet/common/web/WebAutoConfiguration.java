package io.vaullet.common.web;

import io.micrometer.tracing.Tracer;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wires the HTTP edge. Adding {@code backend-common-web} to a service is the whole installation
 * step; there is nothing to import and no annotation to remember.
 *
 * <p>Every bean here is {@code @ConditionalOnMissingBean}, so overriding one is declaring your own —
 * the property that makes an auto-configuration a default rather than a constraint. The most likely
 * override is the exception handler: see {@link ApiExceptionHandler} for how a service adds its own
 * {@code @ExceptionHandler} methods without losing the shared ones.
 *
 * <p>The shared {@code application.yaml} defaults are <em>not</em> applied from here. Auto-configured
 * property defaults are invisible in a way that hurts: someone reading the service's
 * {@code application.yaml} sees no mention of the Jackson naming strategy and reasonably concludes
 * nothing sets it. A service opts in with one explicit, greppable line instead:
 *
 * <pre>{@code
 * spring:
 *   config:
 *     import: "classpath:vaullet/platform-defaults.yaml"
 * }</pre>
 *
 * <p>Imported documents rank <em>below</em> the file that imports them, so any key a service also
 * sets locally still wins.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({ApiProperties.class, OpenApiProperties.class})
public class WebAutoConfiguration {

    /**
     * @param tracer an {@code ObjectProvider} because tracing is not auto-configured in every test
     *     slice, and an error body missing its correlation id beats an error handler that throws
     */
    @Bean
    @ConditionalOnMissingBean
    ApiExceptionHandler apiExceptionHandler(ObjectProvider<Tracer> tracer) {
        return new ApiExceptionHandler(tracer);
    }

    @Bean
    @ConditionalOnMissingBean
    CorsConfigurer vaulletCorsConfigurer(ApiProperties properties) {
        return new CorsConfigurer(properties);
    }

    /**
     * Only when springdoc is on the classpath. The dependency is {@code <optional>} in this module's
     * POM, so a service without a published contract neither declares it nor pays for it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OpenAPI.class)
    @Import(OpenApiConfiguration.class)
    static class OpenApiSupport {}
}
