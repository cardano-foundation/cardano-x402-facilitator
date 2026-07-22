package org.cardanofoundation.x402.facilitator.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * The facilitator's Flyway runner: its migrations run programmatically against
 * their own history table in schema `facilitator` (Spring's single auto-run is
 * disabled in application.yml). yaci-store, when used, runs as a separate service
 * and owns its own schema/migrations entirely — nothing here touches it.
 */
@Configuration
public class FlywayConfig {

    @Bean
    public Flyway facilitatorFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("facilitator")
                .defaultSchema("facilitator")
                .createSchemas(true)
                .load();
    }

    @Bean
    public FlywayMigrationInitializer facilitatorFlywayInitializer(Flyway facilitatorFlyway) {
        return new FlywayMigrationInitializer(facilitatorFlyway);
    }

    /** Database vendor ({@code postgresql} / {@code h2}); used to gate the reconciler's advisory lock. */
    public static String vendor(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName().toLowerCase();
            if (product.contains("postgres")) return "postgresql";
            if (product.contains("h2")) return "h2";
            return product;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve database vendor", e);
        }
    }
}
