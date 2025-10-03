package tech.flowcatalyst.messagerouter.integration;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/**
 * Quarkus test resource that starts PostgreSQL container for integration tests.
 */
public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

    private static final DockerImageName POSTGRES_IMAGE =
        DockerImageName.parse("postgres:16-alpine");

    private PostgreSQLContainer<?> postgresContainer;

    @Override
    public Map<String, String> start() {
        postgresContainer = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("flowcatalyst_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

        postgresContainer.start();

        return Map.of(
            "quarkus.datasource.jdbc.url", postgresContainer.getJdbcUrl(),
            "quarkus.datasource.username", postgresContainer.getUsername(),
            "quarkus.datasource.password", postgresContainer.getPassword(),
            "quarkus.flyway.migrate-at-start", "true"
        );
    }

    @Override
    public void stop() {
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }
}
