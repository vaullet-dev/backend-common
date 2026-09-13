package dev.vaullet.common.error;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * One entry in a service's machine-readable error catalogue.
 *
 * <p>RFC 9457 ({@code application/problem+json}) says the {@code type} URI is what a client branches
 * on — not the HTTP status, and certainly not a prose message. ADR-011, §7 goes one step further and
 * makes a short {@code code} the contract instead, on the grounds that a code is greppable in a
 * support ticket and survives a documentation site move; the URI stays as the RFC-mandated
 * identifier.
 *
 * <h2>Why this is an interface and not the enum it used to be</h2>
 *
 * <p>The natural first move when extracting error handling into a shared library is to bring the
 * enum along, so that the whole platform's codes live in one reviewable list. That list then becomes
 * the thing every service has to modify to ship a domain error, and this library turns into the hub
 * that ADR-002 spent a whole decision removing — a bonus-only code bumping a version that Limits,
 * Audit and Fraud Detection all consume.
 *
 * <p>So the shape is inverted. {@link CommonErrorType} holds the failures that genuinely belong to
 * every HTTP service — validation, not-found, denied, unexpected — and each service declares its own
 * enum for its own vocabulary:
 *
 * {@snippet lang = java:
 * public enum LedgerErrorType implements ErrorType {
 *     INSUFFICIENT_FUNDS("insufficient-funds", HttpStatus.CONFLICT, "Insufficient funds");
 *
 *     private final URI type;
 *     private final HttpStatus status;
 *     private final String title;
 *
 *     LedgerErrorType(String slug, HttpStatus status, String title) {
 *         this.type = ErrorType.documentationUri(slug);
 *         this.status = status;
 *         this.title = title;
 *     }
 *     // accessors...
 * }
 *}
 *
 * <p>An enum remains the right implementation: the set stays closed, reviewable, publishable in the
 * OpenAPI document, and impossible to typo at a call site. What changed is <em>whose</em> set it is.
 *
 * <p>Adding an entry is additive and safe. Changing what an existing entry <em>means</em> is a
 * breaking change for every caller that branches on it, so it needs a new major API version
 * (ADR-011, §4).
 *
 * <h2>When a code should move into this library</h2>
 *
 * <p>When a <em>second</em> service needs it, and not before. A code used by one service is that
 * service's vocabulary however platform-shaped it sounds.
 */
public interface ErrorType {

    /** ADR-011, §7. Resolvable documentation, one page per code. */
    String DOCUMENTATION_BASE = "https://docs.vaullet.dev/errors/";

    /**
     * Builds the RFC 9457 {@code type} URI for a slug, so no implementation has to retype the base.
     *
     * @param slug kebab-case identifier, conventionally the lower-cased {@link #code()}
     */
    static URI documentationUri(String slug) {
        return URI.create(DOCUMENTATION_BASE + slug);
    }

    /**
     * The stable, machine-readable contract (ADR-011): {@code INSUFFICIENT_FUNDS}, and so on.
     *
     * <p>Implemented for free by any enum, whose {@code name()} is exactly this.
     */
    String code();

    /** The RFC 9457 {@code type}: a URI identifying the problem, resolvable to documentation. */
    URI type();

    /** The HTTP status this failure is represented as. */
    HttpStatus status();

    /** Short, human-readable summary. Stable per type — the varying part is {@code detail}. */
    String title();
}
