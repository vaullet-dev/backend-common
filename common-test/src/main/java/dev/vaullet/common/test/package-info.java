/**
 * The test scaffolding every {@code @vaullet-dev} service needs before it can test anything real: a
 * composed {@code @IntegrationTest} annotation, a real PostgreSQL, and the architecture rules.
 *
 * <p>These are shared for a reason that is easy to miss. Spring caches application contexts by their
 * configuration, so a single stray {@code @TestPropertySource} in one test class silently doubles
 * the number of contexts a build starts — and on a service that runs Flyway, doubles the migration
 * runs too. A composed annotation is not brevity, it is the mechanism that keeps that configuration
 * identical across every test class in the fleet.
 *
 * <p>Consume with {@code <scope>test</scope>}. Everything this module pulls in — Testcontainers, the
 * Boot test starters, ArchUnit — is a thing no service should ship.
 */
@NullMarked
package dev.vaullet.common.test;

import org.jspecify.annotations.NullMarked;
