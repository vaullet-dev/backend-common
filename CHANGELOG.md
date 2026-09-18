# Changelog

All notable changes to `@vaullet-dev/backend-common` are recorded here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow
[Semantic Versioning](https://semver.org) — see [Versioning](README.md#versioning) for what MAJOR,
MINOR and PATCH mean for *this* library, which is not quite what they mean for a wire contract.

The version number is never committed. A release is a `vX.Y.Z` tag; `-Drevision` does the rest.
Entries are written under **Unreleased** as the change lands, and the heading is renamed at release
— which is also the moment to check the number against what is listed here.

## [Unreleased]

### Changed — **breaking**

- **The concrete exceptions moved to `dev.vaullet.common.error.exception`.**
  `ResourceNotFoundException`, `ResourceConflictException` and `UpstreamUnavailableException` now live
  one package down; `ErrorType`, `CommonErrorType` and `ApplicationException` stay where they are.
  The rule applied across every repository on 2026-09-17: **the abstract contract stays up, concrete
  throwables go down**. It is a real seam rather than a folder — `exception` depends up on `error`
  and nothing depends down.

  This changes public fully-qualified names, so it is a **MAJOR** change under this library's
  versioning and wants a `0.2.0`. No consumer imported the three classes at the time of the move, so
  nothing broke; a consumer adopting `0.2.0` updates its imports in the same commit that bumps
  `common.version`.

### Added

- `common-core` — `ErrorType` as an interface plus `CommonErrorType`, so a service declares its own
  codes without a pull request against this library
- `common-core` — `ApplicationException` and the general-purpose `ResourceNotFoundException`,
  `ResourceConflictException`, `UpstreamUnavailableException`
- `common-web` — RFC 9457 `problem+json` via `ApiExceptionHandler` and `ProblemDetails`, in the shape
  ADR-011 §7 fixes; property-driven CORS; OpenAPI metadata and the bearer scheme, conditional on
  springdoc being present
- `common-web` — `vaullet/platform-defaults.yaml`, the shared Boot configuration a service imports
  with `spring.config.import`
- `common-security` — the ADR-006 posture: stateless JWT resource server, deny by default, the
  `local` profile chain, method security in every profile
- `common-test` — composed `@IntegrationTest`, `PostgresContainerConfiguration`,
  `TestSecurityConfiguration`, and `ArchitectureRules`
- `common-bom` — dependency management for the four
- Semantic-versioning enforcement: japicmp compares each module against its last release and fails
  the build when the version does not match the change

### Fixed

- **JWT realm roles are read from `realm_access.roles`.** Both copies this library was extracted
  from read a top-level `roles` claim, which returns nothing against a Keycloak token (ADR-006) —
  no exception, no log line, just a `hasRole(...)` rule that silently never matches. Any service
  adopting this library gets the fix; a service still carrying its own copy does not.

### Notes for adopters

- Spring Security 7 contributes a `FACTOR_BEARER` authority to every bearer-token principal. A test
  asserting an exact authority set will fail on it.
- Configuration moves to the `vaullet.*` prefix (`vaullet.api`, `vaullet.security`,
  `vaullet.openapi`), leaving `app.*` free for what a service genuinely owns.

[Unreleased]: https://github.com/vaullet-dev/backend-common/commits/main
