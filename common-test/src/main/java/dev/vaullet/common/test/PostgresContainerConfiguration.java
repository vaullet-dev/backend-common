package dev.vaullet.common.test;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real PostgreSQL for tests, started on demand by Testcontainers.
 *
 * <p>Testing against H2 is worse than not testing. The parts of a Vaullet schema that carry the
 * correctness argument are all PostgreSQL-specific — {@code SELECT ... FOR UPDATE}, partial and
 * functional unique indexes, {@code NUMERIC(20,4)}, {@code TIMESTAMPTZ}, {@code make_interval},
 * append-only {@code DO INSTEAD NOTHING} rules — and an embedded database either rejects the
 * migration or, far worse, accepts it and silently does not enforce it.
 *
 * <p>{@code @ServiceConnection} is the piece that removes the boilerplate: Boot reads the container's
 * host, port and credentials and configures the {@code DataSource} itself. No
 * {@code @DynamicPropertySource} block, no property names to keep in sync.
 *
 * <p>The container is a singleton for the whole JVM because Spring caches application contexts
 * between test classes; a per-class container would restart PostgreSQL for every test class and
 * dominate the build time.
 *
 * <p>The image tag is a property so a service can pin the same tag its {@code compose.yaml} uses —
 * a behaviour that passes locally and fails in CI should never be able to be a version difference.
 * Set {@code vaullet.test.postgres.image} in {@code application-test.yaml} when they differ.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer(
            @Value("${vaullet.test.postgres.image:postgres:18-alpine}") String image) {
        return new PostgreSQLContainer(DockerImageName.parse(image));
    }
}
