package org.cardanofoundation.x402.facilitator.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Standard PostgreSQL initialization keeps both application schemas and journal data. */
class PostgresFlywayIT {
    @Test void standardImageCreatesSchemasAndReusesSettlementJournal() {
        try (var postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("postgres").withUsername("postgres").withPassword("test-admin-only")
                .withEnv("POSTGRES_INITDB_ARGS", "--auth-host=scram-sha-256 --auth-local=trust")
                .withEnv("POSTGRES_HOST_AUTH_METHOD", "scram-sha-256")) {
            postgres.start();
            var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), "postgres", "test-admin-only");
            var facilitator = Flyway.configure().dataSource(ds).schemas("facilitator")
                    .defaultSchema("facilitator").load();
            assertThat(facilitator.migrate().migrationsExecuted).isEqualTo(2);
            var jdbc = new JdbcTemplate(ds);
            jdbc.update("""
                    INSERT INTO facilitator.settlement
                      (tx_hash, attempt_id, requirements_digest, network, status, claimed_at)
                    VALUES (repeat('a', 64), '11111111-1111-1111-1111-111111111111',
                      repeat('b', 64), 'cardano:preprod', 'SUBMITTED', now())
                    """);

            // This is the same Spring Flyway schemas setting used by pinned Yaci Store.
            var yaci = Flyway.configure().dataSource(ds).schemas("yaci_store")
                    .defaultSchema("yaci_store").locations("classpath:db/yaci-test")
                    .load();
            yaci.migrate();
            assertThat(jdbc.queryForObject("SELECT to_regnamespace('yaci_store') IS NOT NULL", Boolean.class))
                    .isTrue();
            facilitator.validate();
            assertThat(facilitator.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM facilitator.settlement WHERE tx_hash = repeat('a', 64)
                    """, String.class)).isEqualTo("SUBMITTED");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM facilitator.flyway_schema_history
                    WHERE success AND type = 'SQL'
                    """, Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_roles WHERE rolname IN ('facilitator', 'yaci_store')
                    """, Integer.class)).isZero();

            var wrong = new DriverManagerDataSource(postgres.getJdbcUrl(), "postgres", "wrong-password");
            assertThatThrownBy(() -> new JdbcTemplate(wrong).queryForObject("SELECT 1", Integer.class))
                    .satisfies(error -> assertThat(
                            org.springframework.core.NestedExceptionUtils.getMostSpecificCause(error).getMessage())
                            .contains("password authentication failed"));
        }
    }
}
