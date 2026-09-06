package io.vaullet.common.security;

import io.micrometer.tracing.Tracer;
import io.vaullet.common.web.ApiExceptionHandler;
import io.vaullet.common.web.WebAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Wires the platform security posture. Adding {@code backend-common-security} is the installation
 * step.
 *
 * <p>{@code before = WebAutoConfiguration.class} is load-bearing rather than tidy: it is what lets
 * {@link SecurityApiExceptionHandler} be registered first, so the web module's
 * {@code @ConditionalOnMissingBean(ApiExceptionHandler.class)} finds it and does not register a
 * second, weaker advice.
 *
 * <p>{@code vaullet.security.enabled=false} switches the whole thing off. It exists for a service
 * that needs a genuinely different chain — not as a way to run without security, which the profile
 * split already handles properly.
 */
@AutoConfiguration(before = WebAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "vaullet.security", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SecurityProperties.class)
@Import({ResourceServerSecurityConfig.class, LocalSecurityConfig.class, MethodSecurityConfig.class})
public class PlatformSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ApiExceptionHandler.class)
    SecurityApiExceptionHandler securityApiExceptionHandler(ObjectProvider<Tracer> tracer) {
        return new SecurityApiExceptionHandler(tracer);
    }
}
