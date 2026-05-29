package io.casehub.claudony.casehub;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts a PostgreSQL container BEFORE Quarkus augmentation.
 * QuarkusTestResourceLifecycleManager.start() properties are applied at the highest
 * config priority — they override the production H2 JDBC URL so the qhorus datasource
 * connects to real PostgreSQL.
 */
public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

    private PostgreSQLContainer<?> postgres;

    @Override
    public Map<String, String> start() {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("qhorus")
                .withUsername("qhorus")
                .withPassword("qhorus");
        postgres.start();

        String reactiveUrl = "vertx-reactive:postgresql://"
                + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/" + postgres.getDatabaseName();

        return Map.of(
                "quarkus.datasource.qhorus.jdbc.url", postgres.getJdbcUrl(),
                "quarkus.datasource.qhorus.reactive.url", reactiveUrl,
                "quarkus.datasource.qhorus.username", postgres.getUsername(),
                "quarkus.datasource.qhorus.password", postgres.getPassword(),
                "quarkus.flyway.qhorus.migrate-at-start", "true",
                "quarkus.flyway.qhorus.locations",
                        "classpath:db/qhorus/migration,classpath:db/ledger/migration",
                "quarkus.hibernate-orm.qhorus.database.generation", "none"
        );
    }

    @Override
    public void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }
}
