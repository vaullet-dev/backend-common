package io.vaullet.common.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Maps Keycloak's claims onto Spring Security authorities.
 *
 * <p>Two sources, because ADR-006 emits two kinds of permission:
 *
 * <ul>
 *   <li>{@code scope} — the standard OAuth2 claim, becoming {@code SCOPE_*}. This is what ADR-008's
 *       service tokens carry ({@code ledger:reserve}, {@code risk:act}).
 *   <li>{@code realm_access.roles} — Keycloak realm roles, becoming {@code ROLE_*}: {@code END_USER},
 *       {@code SUPPORT_AGENT}, {@code FINANCE} and the rest of ADR-006's table.
 * </ul>
 *
 * <p><b>The roles claim is nested, and that is the whole reason this class exists.</b> Keycloak puts
 * roles under {@code realm_access.roles}, not at the top level, so
 * {@code jwt.getClaimAsStringList("roles")} — the obvious implementation, and the one a service
 * writes when it copies a tutorial — returns nothing. Nothing is the dangerous answer: no exception,
 * no log line, just a {@code hasRole('FINANCE')} rule that never matches and an endpoint that
 * appears to be locked down while denying everyone including the people who should get in. The path
 * is configurable ({@code vaullet.security.roles-claim}) because Auth0 namespaces the claim and
 * Entra ID puts it at the top level, but the default matches the identity provider this platform
 * actually runs.
 */
final class JwtAuthorityMapper {

    private JwtAuthorityMapper() {}

    static JwtAuthenticationConverter authenticationConverter(String rolesClaim) {
        var scopes = new JwtGrantedAuthoritiesConverter();

        Converter<Jwt, Collection<GrantedAuthority>> combined = jwt -> Stream.concat(
                        scopes.convert(jwt).stream(), rolesFrom(jwt, rolesClaim).stream())
                .distinct()
                .toList();

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(combined);
        return converter;
    }

    /**
     * Reads a possibly nested claim such as {@code realm_access.roles} and prefixes each value with
     * {@code ROLE_}.
     */
    private static List<GrantedAuthority> rolesFrom(Jwt jwt, String rolesClaim) {
        var roles = resolve(jwt.getClaims(), rolesClaim.split("\\."));
        if (!(roles instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(String::valueOf)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    private static @Nullable Object resolve(Map<String, Object> claims, String[] path) {
        Object current = claims;
        for (var segment : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }
}
