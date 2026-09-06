package io.vaullet.common.web;

import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The platform CORS policy, driven entirely by {@link ApiProperties}.
 *
 * <p>Implementing {@link WebMvcConfigurer} <em>adds</em> to Boot's auto-configuration. Putting
 * {@code @EnableWebMvc} on a class like this would replace that auto-configuration wholesale and
 * quietly cost you content negotiation, message converters and error handling — a classic Spring
 * Boot own-goal, and one worth stating in the library that stops each service from repeating it.
 *
 * <p>Nothing is registered when no origins are configured. That matters: an empty
 * {@code allowedOrigins} list is not "allow nothing" to {@code CorsRegistry}, and registering a
 * mapping with no origins is a different thing from registering no mapping at all.
 */
class CorsConfigurer implements WebMvcConfigurer {

    private final ApiProperties properties;

    CorsConfigurer(ApiProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = properties.allowedOrigins();
        if (origins.isEmpty()) {
            return;
        }
        registry.addMapping(properties.corsPathPattern())
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods(properties.allowedMethods().toArray(String[]::new))
                .allowedHeaders("*")
                .exposedHeaders(properties.exposedHeaders().toArray(String[]::new))
                .allowCredentials(true)
                .maxAge(properties.maxAgeSeconds());
    }
}
