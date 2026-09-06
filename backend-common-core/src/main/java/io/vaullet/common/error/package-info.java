/**
 * The error vocabulary shared by every {@code @vaullet-io} backend service.
 *
 * <p>Everything here is deliberately free of the web stack. A service layer throws in domain terms;
 * translating that into an HTTP response is {@code backend-common-web}'s job, and happens once. That
 * separation is what keeps the same rules callable from a Kafka listener, a scheduled sweeper or a
 * plain unit test — none of which have a request to bind (ADR-004's revised flow settles money from
 * a listener, so this is not a hypothetical).
 *
 * <p>{@link org.jspecify.annotations.NullMarked} makes every type in this package and all
 * sub-packages null-<em>hostile</em> by default: references are non-null unless explicitly marked
 * {@code @Nullable}. Spring Framework 7 ships JSpecify annotations throughout its own API, so IDEs
 * and NullAway/Error Prone can flag a possible {@code NullPointerException} at compile time instead
 * of at 3am.
 */
@NullMarked
package io.vaullet.common.error;

import org.jspecify.annotations.NullMarked;
