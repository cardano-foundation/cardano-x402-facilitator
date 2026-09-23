package org.cardanofoundation.x402.facilitator.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The real Compose bootstrap permits immutable Flyway V1/V2 under the restricted role. */
class PostgresRoleFlywayIT {
    @Test void restrictedRoleOwnsFlywayHistoryAndSettlement() {
        String script = Path.of("deploy/postgres/10-roles.sh").toAbsolutePath().toString();
        try (var postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("postgres").withUsername("postgres").withPassword("test-admin-only")
                .withEnv("FACILITATOR_DB_PASSWORD", "test-facilitator-only")
                .withEnv("YACI_DB_PASSWORD", "test-yaci-only")
                .withEnv("POSTGRES_INITDB_ARGS", "--auth-host=scram-sha-256 --auth-local=trust")
                .withEnv("POSTGRES_HOST_AUTH_METHOD", "scram-sha-256")
                .withCopyFileToContainer(MountableFile.forHostPath(script),
                        "/docker-entrypoint-initdb.d/10-roles.sh")) {
            postgres.start();
            var ds = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    "facilitator", "test-facilitator-only");
            var flyway = Flyway.configure().dataSource(ds).schemas("facilitator")
                    .defaultSchema("facilitator").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            flyway.validate();
            var jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM facilitator.flyway_schema_history WHERE success", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM facilitator.settlement", Integer.class)).isZero();
        }
    }
}
