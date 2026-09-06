/**
 * The security posture every {@code @vaullet-io} backend service runs, per ADR-006 and ADR-008.
 *
 * <p>ADR-006's internal contract is that <b>Keycloak is the only token signer</b>: every service
 * validates exactly one token format, against one JWKS, with one claim set. That is a platform
 * decision, and a platform decision implemented fourteen times is fourteen chances to get the claim
 * mapping subtly wrong — which is not a bug that shows up as a failed test, it is a bug that shows
 * up as an authorisation rule that silently never matches.
 *
 * <p>So the chain, the authority mapping and the local-development escape hatch live here, and a
 * service configures the parts that are genuinely its own: the issuer, its public paths, and the
 * scopes its {@code @PreAuthorize} rules ask for.
 */
@NullMarked
package io.vaullet.common.security;

import org.jspecify.annotations.NullMarked;
