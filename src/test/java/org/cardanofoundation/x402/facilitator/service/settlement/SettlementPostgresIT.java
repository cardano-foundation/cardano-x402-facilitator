package org.cardanofoundation.x402.facilitator.service.settlement;

import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.protocol.SettleResponse;
import org.cardanofoundation.x402.facilitator.repository.SettlementRepository;
import org.cardanofoundation.x402.facilitator.service.verification.ExactCardanoScheme;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.service.verification.method.DefaultTransferVerifier;
import org.cardanofoundation.x402.facilitator.testutil.FakeChainService;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec-mandated PostgreSQL correctness suite (section 15): real ON CONFLICT
 * claim atomicity across TWO independent repository "contexts" against ONE
 * database, advisory-lock exclusivity, and JSON round-trip — things H2 cannot
 * faithfully validate.
 */
@Testcontainers(disabledWithoutDocker = true)
class SettlementPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static SettlementRepository repoA;
    static SettlementRepository repoB;
    static JdbcTemplate plainA;
    static JdbcTemplate plainB;

    FakeChainService chain;
    SettlementService serviceA;
    SettlementService serviceB;

    @BeforeAll
    static void initDb() {
        Flyway.configure().dataSource(ds()).locations("classpath:db/migration")
                .schemas("facilitator").defaultSchema("facilitator").createSchemas(true).load().migrate();
        repoA = new SettlementRepository(new NamedParameterJdbcTemplate(ds()));
        repoB = new SettlementRepository(new NamedParameterJdbcTemplate(ds()));
        plainA = new JdbcTemplate(ds());
        plainB = new JdbcTemplate(ds());
    }

    static DriverManagerDataSource ds() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    @BeforeEach
    void setUp() {
        plainA.update("DELETE FROM facilitator.settlement");
        chain = new FakeChainService();
        chain.unspent.put(TestTx.NONCE, TestTx.PAYER_ADDRESS);
        chain.currentSlot = 500_000L;
        serviceA = service(repoA);
        serviceB = service(repoB);
    }

    SettlementService service(SettlementRepository repo) {
        ExactCardanoScheme scheme = new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier()), 32768);
        return new SettlementService(repo, scheme, chain, new CardanoTransactionDecoder(),
                new SettlementService.Config(Duration.ofSeconds(2), 1, false, false,
                        Duration.ofMinutes(10), Duration.ofSeconds(2)),
                Clock.systemUTC());
    }

    PaymentRequirements requirements() {
        return new PaymentRequirements("exact", "cardano:preprod", "lovelace", "2000000",
                TestTx.PAY_TO, 600, Map.of("assetTransferMethod", "default"));
    }

    PaymentPayload payload(String txB64) {
        Map<String, Object> p = new HashMap<>();
        p.put("transaction", txB64);
        p.put("nonce", TestTx.NONCE);
        return new PaymentPayload(2, Map.of("url", "https://example.test/a"), requirements(), p, null);
    }

    @Test
    void crossContextRaceSubmitsExactlyOnce() throws Exception {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            SettlementService svc = (i % 2 == 0) ? serviceA : serviceB; // two app "contexts"
            futures.add(pool.submit(() -> {
                start.await();
                SettleResponse r = svc.settle(payload(tx), requirements());
                if (r.success()) successes.incrementAndGet();
                else if (ErrorCodes.DUPLICATE_SETTLEMENT.equals(r.errorReason())) duplicates.incrementAndGet();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        assertThat(successes.get()).isEqualTo(1);
        assertThat(duplicates.get()).isEqualTo(threads - 1);
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void advisoryLockIsExclusiveAcrossHeldSessions() throws Exception {
        // Advisory locks are SESSION-scoped: they only exclude while the acquiring
        // connection stays open (which is why the reconciler holds one dedicated
        // connection for the whole sweep).
        try (Connection sessionA = ds().getConnection(); Connection sessionB = ds().getConnection();
             Statement stA = sessionA.createStatement(); Statement stB = sessionB.createStatement()) {
            try (ResultSet rs = stA.executeQuery("SELECT pg_try_advisory_lock(42)")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
            }
            try (ResultSet rs = stB.executeQuery("SELECT pg_try_advisory_lock(42)")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isFalse(); // excluded while session A holds it
            }
            stA.execute("SELECT pg_advisory_unlock(42)");
            try (ResultSet rs = stB.executeQuery("SELECT pg_try_advisory_lock(42)")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue(); // released -> B can take it
            }
            stB.execute("SELECT pg_advisory_unlock(42)");
        }
    }

    @Test
    void responseJsonRoundTripsThroughPostgres() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        SettleResponse r = serviceA.settle(payload(tx), requirements());
        assertThat(r.success()).isTrue();
        SettlementRecord rec = repoB.find(r.transaction()).orElseThrow();
        assertThat(rec.responseJson()).contains("\"success\":true").contains(r.transaction());
        assertThat(rec.status()).isEqualTo(SettlementRecord.Status.CONFIRMED);
    }
}
