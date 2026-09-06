package io.vaullet.common.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Developer-machine security: everything open, no identity provider required.
 *
 * <p>Active only under the {@code local} profile, which a service's {@code spring-boot-maven-plugin}
 * block sets for {@code spring-boot:run}. A plain {@code java -jar} gets
 * {@link ResourceServerSecurityConfig} and therefore refuses to start without an issuer — the safe
 * default is the one that applies in production, and the permissive one is the special case that has
 * to be asked for.
 *
 * <p>{@link ResourceServerSecurityConfig} carries the mirror-image {@code @Profile("!local")}, so
 * exactly one filter chain is ever active.
 */
@Profile("local")
@Configuration(proxyBeanMethods = false)
class LocalSecurityConfig {

    private final SecurityProperties properties;

    LocalSecurityConfig(SecurityProperties properties) {
        this.properties = properties;
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Method security stays switched on, so the anonymous principal is handed the
                // authorities the @PreAuthorize rules ask for. Local development exercises the real
                // rules rather than a version of the app with authorisation compiled out.
                .anonymous(anonymous -> anonymous.authorities(
                        properties.local().anonymousAuthorities().toArray(String[]::new)))
                .build();
    }
}
