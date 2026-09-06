# backend-common

The plumbing every `@vaullet-io` backend service needs and none of them should own a copy of, per
[ADR-013](../architecture/docs/adr/013-backend-common-shared-library.md).

Four artifacts, split by **what they drag onto a classpath**. Take the ones you need.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.vaullet</groupId>
      <artifactId>backend-common-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.vaullet</groupId>
    <artifactId>backend-common-web</artifactId>
  </dependency>
  <dependency>
    <groupId>io.vaullet</groupId>
    <artifactId>backend-common-security</artifactId>
  </dependency>
  <dependency>
    <groupId>io.vaullet</groupId>
    <artifactId>backend-common-test</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Everything is auto-configured. Adding the dependency is the whole installation step, and overriding
anything is declaring the bean you want instead.

---

## Why this exists

The ledger service and the `spring-boot-template` had near-identical copies of the same fourteen
files: the error-response shape, the security chain, CORS, OpenAPI metadata, Testcontainers wiring,
ArchUnit rules. 766 lines in the ledger alone, several of them byte-identical to the template's. [ADR-005](../architecture/docs/adr/005-module-composition-and-deployment-topology.md)
plans fourteen services.

The cost of copying is not keystrokes. It is that **the copies drift silently**. The ledger's copy of
the JWT authority mapping read roles from a top-level `roles` claim;
[ADR-006](../architecture/docs/adr/006-authentication-and-identity.md) has Keycloak emit them under
`realm_access.roles`. That difference throws nothing and logs nothing — it just means
`hasRole('FINANCE')` never matches, on the service that guards the money. Nothing in the ledger's test
suite was looking for it, because nothing in a service's test suite ever looks at code it inherited by
copy-paste.

That bug is fixed here, and pinned by a test.

---

## The modules

| Module | What you get | What it drags in |
| --- | --- | --- |
| **`core`** | `ErrorType`, `ApplicationException` and general-purpose subclasses | `spring-web` (for `HttpStatus`), JSpecify |
| **`web`** | problem+json advice, `ProblemDetails`, CORS, OpenAPI metadata, `platform-defaults.yaml` | `spring-boot-starter-webmvc`, `-validation`, `micrometer-tracing`, `spring-tx` |
| **`security`** | JWT resource-server chain, Keycloak claim mapping, method security | `spring-boot-starter-security`, `-security-oauth2-resource-server` |
| **`test`** | `@IntegrationTest`, PostgreSQL container, `JwtDecoder` stand-in, ArchUnit rules | Testcontainers, the Boot test starters, ArchUnit |
| **`bom`** | Dependency management for the four | — |

`web` depends on `core`; `security` depends on `web`; `test` stands alone.

**The split axis is the dependency footprint, not the topic.** That is the whole design. A Kafka
worker that raises `ResourceNotFoundException` takes `core` and gets no servlet API; a service behind
the mesh with no application-level authentication takes `web` and gets no Spring Security. A single
fat jar would put `spring-webmvc` on the classpath of the listener that ADR-004 settles money from,
and the service-layer/HTTP separation that path depends on would erode in a month.

It is also the answer to [ADR-002](../architecture/docs/adr/002-shared-contracts-versioning-strategy.md),
which rejected a fat shared jar for fourteen services because it rebuilds the build-graph hub that
polyrepo exists to remove. Its remedy — split the published unit, keep one repository — is applied
here on a different axis: contracts couple by domain, plumbing couples by dependency.

### This is not `wallet-shared-contracts`

|  | `wallet-shared-contracts` (ADR-002) | `backend-common` |
| --- | --- | --- |
| Contains | DTOs and event schemas — **what goes on the wire** | **How a service is built** |
| Changes when | A contract changes | Spring changes, or a platform practice does |
| Breaking change means | Producers and consumers must coordinate | A compile error in one service |

They touch in exactly one place, and cleanly: `contracts-common`'s `Problem` is what a **client**
deserialises; `backend-common-web` produces that body **server-side**. Neither generates the other.

---

## `core` — the error vocabulary

A service layer throws in domain terms and never sees `ResponseEntity`, `HttpStatus` or a servlet
type. Translating to HTTP happens once, in `web`. That separation is not tidiness: ADR-004's revised
flow settles and releases money from a Kafka listener, which has no request to bind.

### `ErrorType` is an interface

The obvious extraction is to bring the whole error enum along, so the platform's codes live in one
reviewable list. That list then becomes the thing every service must modify to ship a domain error,
and this library turns into ADR-002's hub — one enum constant at a time.

So `CommonErrorType` holds only what every HTTP service has, and each service declares its own:

```java
public enum LedgerErrorType implements ErrorType {
    INSUFFICIENT_FUNDS("insufficient-funds", HttpStatus.CONFLICT, "Insufficient funds");

    LedgerErrorType(String slug, HttpStatus status, String title) {
        this.type = ErrorType.documentationUri(slug);   // https://docs.vaullet/errors/<slug>
        ...
    }
}
```

**The membership test is not "does this sound platform-wide" but "would a service that knows nothing
about money still raise it".** `INSUFFICIENT_FUNDS` and `CURRENCY_MISMATCH` fail it and live in the
ledger, even though [ADR-011](../architecture/docs/adr/011-api-versioning-and-openapi.md) §7 names
both as platform catalogue entries. A code graduates into `CommonErrorType` when a **second** service
raises it — never in anticipation of one.

| `CommonErrorType` | Status |
| --- | --- |
| `VALIDATION_FAILED` | 400 |
| `RESOURCE_NOT_FOUND` | 404 |
| `RESOURCE_CONFLICT` | 409 |
| `RESOURCE_BUSY` | 503 + `Retry-After` |
| `UPSTREAM_UNAVAILABLE` | 503 |
| `ACCESS_DENIED` | 403 |
| `INTERNAL_ERROR` | 500 |

---

## `web` — one error body, one CORS policy, one OpenAPI shape

`ApiExceptionHandler` extends Spring's `ResponseEntityExceptionHandler`, so the ~15 MVC exceptions
already arrive as `ProblemDetail`, and adds one handler per *domain* failure. The single
`@ExceptionHandler(ApplicationException.class)` is what lets a service add an error without touching
this library.

The body is ADR-011 §7:

```json
{
  "type": "https://docs.vaullet/errors/insufficient-funds",
  "title": "Insufficient funds",
  "status": 409,
  "detail": "available 40.0000 < requested 60.0000",
  "instance": "/v1/reservations",
  "code": "INSUFFICIENT_FUNDS",
  "trace_id": "68f0a1..."
}
```

**`code` is the contract; `detail` is for humans.** `ApiExceptionHandlerTest` asserts that shape at
the wire — it belongs here rather than in a service, because when the shape drifts, no service's own
suite is looking.

### Extending it

Subclass and annotate `@RestControllerAdvice`; the auto-configuration backs off. Override
`lockContentionErrorType()` when the service has a better name for the contended thing — the ledger
returns its published `ACCOUNT_BUSY` rather than `RESOURCE_BUSY`, in ten lines, because renaming a
published code is a breaking change under ADR-011 §4.

Subclassing beats a second advice: `ApiExceptionHandler` has a catch-all
`@ExceptionHandler(Exception.class)`, so a separate advice needs an `@Order` to beat it — and an
ordering exercised only by a failing request is an ordering that gets broken unnoticed.

### Shared `application.yaml`

Virtual threads, snake_case JSON, problem+json for framework errors, graceful shutdown, Actuator
exposure, tracing sampling, log correlation. Imported, never injected:

```yaml
spring:
  config:
    import: "classpath:vaullet/platform-defaults.yaml"
```

An imported document ranks **below** the file importing it, so anything a service sets locally still
wins. It is an explicit import rather than an auto-configured property source on purpose: a reader of
a service's `application.yaml` who sees no mention of the Jackson naming strategy will reasonably
conclude nothing sets it. One greppable line fixes that.

### OpenAPI

springdoc is `<optional>` — declare it in the service and `OpenApiConfiguration` switches on via
`@ConditionalOnClass`. It contributes the info block and the bearer scheme, so "Authorize" in Swagger
UI works everywhere.

It deliberately declares **no** `GroupedOpenApi` beans. ADR-011 §5 requires one fragment per sellable
module, selected by controller package, and that mapping is knowledge only the service has — a
controller in the wrong group ships an endpoint to a customer who did not buy it.

---

## `security` — the ADR-006 posture

Stateless JWT resource server, deny by default, no sessions and therefore no CSRF, method security on
in **every** profile including `local`.

Two mutually exclusive chains:

| Profile | Chain | Posture |
| --- | --- | --- |
| `!local` | `ResourceServerSecurityConfig` | Requires an issuer. Without a `JwtDecoder` the context refuses to start — a service that silently starts unauthenticated is worse than one that will not boot |
| `local` | `LocalSecurityConfig` | Everything open, method security still on, anonymous principal handed the authorities your `@PreAuthorize` rules ask for |

Local development therefore exercises the *real* authorisation rules rather than a build with
authorisation compiled out.

### The claim mapping

Scopes become `SCOPE_*`; realm roles become `ROLE_*`, read from **`realm_access.roles`** per ADR-006.

This is the bug the library exists to stop repeating. `jwt.getClaimAsStringList("roles")` — the
obvious implementation, and the one a tutorial gives you — returns nothing against a Keycloak token,
and nothing is the dangerous answer: no exception, no log line, just a rule that never matches. The
path is configurable, because Auth0 namespaces the claim and Entra ID puts it at the top level, but
the default matches the IdP this platform actually runs.

> **Note for anyone writing authorization rules:** Spring Security 7 adds a `FACTOR_BEARER` authority
> of its own to every bearer-token principal. A test asserting an exact authority set will fail on it.
> `JwtAuthorityMapperTest` pins this so it is discovered here and not in your service.

### Configuration

```yaml
vaullet:
  security:
    public-paths: /actuator/health, /actuator/health/**, /actuator/info, /v3/api-docs/**, /swagger-ui/**
    actuator-role: OPERATOR              # everything past health/info
    roles-claim: realm_access.roles
    local:
      anonymous-authorities: SCOPE_ledger:read, SCOPE_ledger:write, ROLE_OPERATOR
```

---

## `test` — the scaffolding, and the architecture rules

```java
@IntegrationTest   // full app, real PostgreSQL, real security chain, MockMvc, profile `test`
class OverdraftIT { }
```

A composed annotation, not brevity. Spring caches application contexts **by their configuration**, so
one stray `@TestPropertySource` on one class silently doubles the number of contexts a build starts —
and on a service running Flyway, doubles the migration runs too. Identical configuration everywhere is
the mechanism, and a composed annotation is how you get it.

`PostgresContainerConfiguration` starts a real PostgreSQL, pinned via
`vaullet.test.postgres.image` so it can match the service's `compose.yaml`. Testing this platform
against H2 would be worse than not testing: `SELECT … FOR UPDATE`, partial indexes, `NUMERIC(20,4)`,
`TIMESTAMPTZ` and append-only rules either fail to migrate or — far worse — migrate and silently do
not enforce.

`TestSecurityConfiguration` supplies a `JwtDecoder` that **throws**, so the production chain can be
assembled while a test that accidentally sends a real bearer token fails loudly instead of silently
authenticating.

### `ArchitectureRules`

```java
@AnalyzeClasses(packages = "io.vaullet.ledger", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringTest {
    @ArchTest static final ArchRule layers = ArchitectureRules.layersAreRespected("io.vaullet.ledger");
    @ArchTest static final ArchRule http   = ArchitectureRules.serviceLayerKnowsNothingAboutHttp("io.vaullet.ledger");
    @ArchTest static final ArchRule jdbc   = ArchitectureRules.persistenceStaysInTheDaoLayer(
            "io.vaullet.ledger", "org.springframework.jdbc..");
}
```

Shared rather than copied, because **a rule that is copied is a rule that gets edited locally**. A
service that finds one of these genuinely wrong should change it here, for everyone — that
conversation is the value, and a local edit skips it.

`persistenceStaysInTheDaoLayer` takes the packages as an argument because the platform runs two access
technologies: JPA services pass `jakarta.persistence..`, and a service that rejected an ORM because
the SQL *is* the design (ADR-004) passes `org.springframework.jdbc..`. One boundary, two
technologies.

---

## Working on this repository

```bash
./mvnw test        # 14 tests, no Docker, ~5s
./mvnw install     # publishes to ~/.m2 so a consuming service resolves 0.1.0-SNAPSHOT
```

Nothing here is a Spring Boot application, and `spring-boot-maven-plugin` is deliberately never
declared: a library repackaged into a fat jar cannot be depended on, which is the most common way a
"common" module becomes unusable. The parent inherits from `spring-boot-starter-parent` for its
dependency management and plugin configuration only, and **consumers do not inherit from this POM** —
they inherit from `spring-boot-starter-parent` as usual and import the BOM, so a service can sit on a
different Boot patch version than the one this was built against.

A sources jar is attached to every module. The rationale comments in these classes *are* the
documentation, and a service that only has the binary should still be able to read them.

### Adding something

Three questions, in order:

1. **Would a service that knows nothing about money need it?** If not, it belongs in the service.
2. **Which module?** Decide by what it drags onto a classpath, not by what it is about.
3. **Can it be overridden?** Every bean is `@ConditionalOnMissingBean`. An auto-configuration that
   cannot be overridden is a constraint, and a constraint belongs in a review checklist, not a jar.

### Versioning

Semantic, one version across all four modules — they share the `ErrorType` interface and the
`vaullet.*` prefix, so a mixed combination is not supported. Nothing here is on the wire, so an
upgrade is never forced: services on different versions interoperate exactly as before.

---

## Status

| Consumer | State |
| --- | --- |
| `wallet-ledger-service` | ✅ Migrated. Fourteen files and 766 lines removed, Java down 2,763 → 2,105; 20 unit/slice + 18 integration tests green |
| `spring-boot-template` | ⬜ Pending — and it should be next. A template that does not use the library teaches every new service not to |

Open: publish `0.1.0` to GHCR Maven so consuming CI stops needing a local `mvn install`; decide
whether `PageResponse` (offset-shaped, currently only in the template) belongs here at all, given
ADR-011 §8 mandates cursor pagination.

---

The comments in the code are the documentation. If you change a decision, change the comment that
explains it; a library whose rationale has gone stale is worse than one with none.
