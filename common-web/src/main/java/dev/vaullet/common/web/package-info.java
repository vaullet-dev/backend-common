/**
 * The HTTP edge every {@code @vaullet-dev} service shares: one error body, one CORS policy, one
 * OpenAPI shape, one set of {@code application.yaml} defaults.
 *
 * <p>Nothing in here is a decision a service should be making twice. The RFC 9457 body shape is
 * fixed by ADR-011, §7; the wire conventions (snake_case, decimal-string money, RFC 3339 UTC) are
 * fixed by ADR-011, §8; and both are the kind of thing that drifts silently when each service owns
 * a copy — one endpoint answering in camelCase is a bug no test in that service is looking for.
 *
 * <p>Everything is auto-configured and backs off when a service declares its own bean, so adopting
 * the module is adding a dependency and overriding it is declaring the bean you want.
 */
@NullMarked
package dev.vaullet.common.web;

import org.jspecify.annotations.NullMarked;
