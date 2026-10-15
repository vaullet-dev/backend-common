# Changelog

All notable changes to `@vaullet-dev/backend-common` are recorded here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow
[Semantic Versioning](https://semver.org) — see [Versioning](README.md#versioning) for what MAJOR,
MINOR and PATCH mean for *this* library, which is not quite what they mean for a wire contract.

The version number is never committed. A release is a `vX.Y.Z` tag; `-Drevision` does the rest.
Entries are written under **Unreleased** as the change lands, and the heading is renamed at release
— which is also the moment to check the number against what is listed here.

## [Unreleased]

### Fixed

- `common-web` — **The document is described as OpenAPI 3.1, the version springdoc writes**
  (backend-common#19). 1.1.0's model resolver was built for 3.0, so inside springdoc's 3.1 document
  every object schema lost its `type`, a description next to a `$ref` was dropped, the money example
  `60.00` came out as `60.0`, and an array's items took the array's description and example. The
  hand-built `Problem` and `FieldError` schemas set only the 3.0 `type`, so every field of the error
  body was untyped as well. The resolver is now built with `Json31.mapper()` and `openapi31`
  (following `springdoc.api-docs.version`), the error schemas from swagger's typed schema classes, and
  `OpenApiDocumentTest` reads the served document to hold it there.
- `common-web` — **A service's own `ModelConverter` runs again.** springdoc puts each converter bean
  at the front of swagger-core's chain, and the 1.1.0 resolver, which ends the chain, could land in
  front of a converter the service declares and skip it without a word. The resolver is now ordered
  first, so it runs last.
- `common-web` — **A service with swagger's models but no springdoc starts again.** The OpenAPI
  configuration switched on by swagger's `OpenAPI` class, and since 1.1.0 it also needs springdoc and
  swagger-core, so such a service failed startup with `NoClassDefFoundError`. It now switches on by
  springdoc's own class.
- `common-web` — **A naming strategy given as a class name no longer stops startup.** Spring Boot
  takes one for `spring.jackson.property-naming-strategy`, and 1.1.0 refused to start over the
  document alone. The document now keeps the Java names and logs a warning, as validation errors
  already did.
- `common-web` — **`code` and `type` are no longer required in the `Problem` schema.** A 400 Spring
  raises itself (a malformed body, a path variable that is not a UUID) has no `code`, and no `type`,
  which RFC 9457 reads as `about:blank`, so a client validating responses against the document
  rejected it. `code` is still described as the field to branch on.
- `common-web` — The success response `OpenApiResponses` adds comes first, so Swagger UI lists it
  ahead of the errors rather than after them.

### Notes for adopters

- Validation errors translate the field path of a query-parameter object by the same strategy as a
  body's. That matches the wire when its parameters are named `@BindParam("first_name")`; a multi-word
  query parameter bound by its Java name is reported in snake_case. Documented, unchanged since 1.1.0.

No public API changes: **PATCH**, `1.1.1`.

## [1.1.0] — 2026-10-15

### Added

- `common-web` — **The OpenAPI document describes errors as they are sent.** Every 4xx and 5xx is
  `application/problem+json` with the `Problem` schema (`FieldError` for its `errors`), the body
  `ProblemDetails` builds, instead of the success body springdoc put on every response. An operation
  that declares only its errors gets its success response back, with the status its `@ResponseStatus`
  names. `platform-defaults.yaml` describes `application/json` instead of springdoc's `*/*`.

### Fixed

- `common-web` — **Swagger UI names fields as the wire does.** swagger-core describes the models with
  its own Jackson 2 mapper, which never sees `spring.jackson.property-naming-strategy`, so the document
  said `externalRef` for a body the service reads only as `external_ref`, and a body built from it in
  "Try it out" had every multi-word field ignored. The model resolver now takes the same strategy.
- `common-web` — **Validation errors name a field as the client sent it**: `errors[].field` is
  `identity.first_name`, not `identity.firstName`, under the same strategy.

New beans (the model resolver backs off when a service declares its own) and a new interface on
`ApiExceptionHandler`: **MINOR**, `1.1.0`.

## [1.0.0] — 2026-10-13

### Changed — **breaking**

- **The concrete exceptions moved to `dev.vaullet.common.error.exception`.**
  `ResourceNotFoundException`, `ResourceConflictException` and `UpstreamUnavailableException` now live
  one package down; `ErrorType`, `CommonErrorType` and `ApplicationException` stay where they are.
  The rule applied across every repository on 2026-09-17: **the abstract contract stays up, concrete
  throwables go down**. It is a real seam rather than a folder — `exception` depends up on `error`
  and nothing depends down.

  This changes public fully-qualified names, so it is a **MAJOR** change, and the release that
  carries it is `1.0.0`. japicmp checks `0.x` like any other version
  (`breakBuildBasedOnSemanticVersioningForMajorVersionZero`): on 2026-09-18 it refused the move as
  `0.1.2` (*"Versions of archives indicate a patch change but binary incompatible changes found"*),
  and nothing has been published since `0.1.1`. The commit that releases it has to say so, with `!`
  after the type or a `BREAKING CHANGE:` footer; otherwise `scripts/version.sh` computes `0.2.0` and the
  gate fails again. wallet-auth-service imports all three by now and updates its imports in the
  commit that bumps `common.version` (wallet-auth-service#35).

### Added

- `common-web` — **Swagger UI signs in through Keycloak** (backend-common#16). Next to `bearer-jwt`,
  the OpenAPI document offers `keycloak`: authorization code with PKCE, with Keycloak's endpoints
  under `spring.security.oauth2.resourceserver.jwt.issuer-uri`, so a service sets nothing. No issuer
  (the `local` profile), no scheme. `platform-defaults.yaml` presets the client, `swagger-ui`, which
  gitops creates; `vaullet.openapi.oauth2.*` switches it off or points it at another issuer.
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

[Unreleased]: https://github.com/vaullet-dev/backend-common/compare/v1.1.0...master
[1.1.0]: https://github.com/vaullet-dev/backend-common/releases/tag/v1.1.0
[1.0.0]: https://github.com/vaullet-dev/backend-common/releases/tag/v1.0.0

