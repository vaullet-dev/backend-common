package io.vaullet.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The claim mapping, which is the part of the security posture that fails silently.
 *
 * <p>A wrong filter chain throws; a wrong claim path returns an empty authority list, and an empty
 * authority list looks exactly like a principal who is legitimately not permitted. These tests are
 * the only thing standing between "roles are read from {@code realm_access.roles}" being true and
 * being merely intended.
 */
class JwtAuthorityMapperTest {

    @Test
    @DisplayName("Keycloak realm roles are read from the nested claim ADR-006 specifies")
    void readsNestedRealmRoles() {
        var jwt = token(Map.of(
                "scope", "ledger:read ledger:write",
                "realm_access", Map.of("roles", List.of("FINANCE", "SUPPORT_AGENT"))));

        assertThat(authorities(jwt, "realm_access.roles"))
                .containsExactlyInAnyOrder(
                        "SCOPE_ledger:read", "SCOPE_ledger:write", "ROLE_FINANCE", "ROLE_SUPPORT_AGENT");
    }

    @Test
    @DisplayName("a top-level claim path still works, for an IdP that is not Keycloak")
    void readsTopLevelRoles() {
        var jwt = token(Map.of("roles", List.of("OPERATOR")));

        assertThat(authorities(jwt, "roles")).containsExactly("ROLE_OPERATOR");
    }

    @Test
    @DisplayName("a token with no roles claim yields scopes only, and does not blow up")
    void toleratesAMissingClaim() {
        var jwt = token(Map.of("scope", "ledger:read"));

        assertThat(authorities(jwt, "realm_access.roles")).containsExactly("SCOPE_ledger:read");
    }

    @Test
    @DisplayName("a claim of the wrong shape is ignored rather than throwing mid-request")
    void toleratesAMalformedClaim() {
        var jwt = token(Map.of("realm_access", "not-an-object"));

        assertThat(authorities(jwt, "realm_access.roles")).isEmpty();
    }

    @Test
    @DisplayName("Spring Security 7 contributes FACTOR_BEARER on top of the mapped claims")
    void frameworkAddsItsOwnFactorAuthority() {
        // Not this class's doing, and worth pinning: anyone writing an authorization rule sees this
        // authority on every bearer-token principal, and a test elsewhere that asserts an exact
        // authority set will fail on it rather than on anything it meant to check.
        var all = allAuthorities(token(Map.of("scope", "ledger:read")), "realm_access.roles");

        assertThat(all).contains("FACTOR_BEARER");
    }

    // ---------------------------------------------------------------------

    /** Only the authorities this mapper is responsible for — see {@link #frameworkAddsItsOwnFactorAuthority()}. */
    private static List<String> authorities(Jwt jwt, String rolesClaim) {
        return allAuthorities(jwt, rolesClaim).stream()
                .filter(authority -> authority.startsWith("SCOPE_") || authority.startsWith("ROLE_"))
                .toList();
    }

    private static List<String> allAuthorities(Jwt jwt, String rolesClaim) {
        var authentication = JwtAuthorityMapper.authenticationConverter(rolesClaim).convert(jwt);
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    private static Jwt token(Map<String, Object> claims) {
        return new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(900),
                Map.of("alg", "RS256"),
                claims);
    }
}
