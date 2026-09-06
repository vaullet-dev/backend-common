package io.vaullet.common.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT resource-server security — the default posture for a Vaullet backend service.
 *
 * <p>Practice this encodes, and the reason each part is not left to a service:
 *
 * <ul>
 *   <li><b>Lambda DSL only.</b> {@code WebSecurityConfigurerAdapter} was removed in Spring Security
 *       6 and the non-lambda DSL in 7; a {@link SecurityFilterChain} bean is the only way now.
 *   <li><b>Deny by default.</b> The chain ends in {@code anyRequest().authenticated()}, so a new
 *       endpoint is protected the moment it is written. Opening a path is an explicit act, and it is
 *       a reviewable line of configuration ({@code vaullet.security.public-paths}) rather than a
 *       matcher buried in a class.
 *   <li><b>No sessions, no CSRF.</b> A token-authenticated API creates no session, so there is no
 *       session-riding attack for CSRF to defend against. Disabling it on a <em>cookie</em>-
 *       authenticated app would be a real vulnerability — the distinction matters, and is exactly
 *       the kind of nuance that gets lost when this file is copy-pasted between services.
 *   <li><b>Authorities come from the token</b>, mapped by {@link JwtAuthorityMapper}.
 * </ul>
 *
 * <p>Disabled under the {@code local} profile, where {@link LocalSecurityConfig} takes over. It needs
 * an issuer ({@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, conventionally from
 * {@code OAUTH2_ISSUER_URI}); without a {@code JwtDecoder} the context fails to start. That is
 * intentional — a service that silently starts unauthenticated is worse than one that refuses to
 * boot.
 */
@Profile("!local")
@Configuration(proxyBeanMethods = false)
class ResourceServerSecurityConfig {

    private final SecurityProperties properties;

    ResourceServerSecurityConfig(SecurityProperties properties) {
        this.properties = properties;
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                properties.publicPaths().toArray(String[]::new))
                        .permitAll()
                        // Pre-flight requests never carry credentials.
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        // Everything the Actuator exposes beyond health/info is operator-only.
                        .requestMatchers("/actuator/**")
                        .hasRole(properties.actuatorRole())
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(JwtAuthorityMapper.authenticationConverter(
                                properties.rolesClaim()))))
                // Do not leak a WWW-Authenticate browser prompt from a JSON API.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }
}
