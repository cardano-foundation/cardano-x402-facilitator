package org.cardanofoundation.x402.facilitator.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Two-runner Flyway design (spec/plan): the facilitator's migrations always run
 * with their own history table in schema `facilitator`; yaci-store's migrations
 * (db/store/{vendor}, schema `store`, own history) are added by the yaci mode
 * (phase P6). Separate histories mean mode switches can never re-run or orphan
 * either side. Spring's single auto-run is disabled in application.yml.
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

    /** Resolves Spring's {vendor} token for programmatic runners (P6 store migrations). */
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
