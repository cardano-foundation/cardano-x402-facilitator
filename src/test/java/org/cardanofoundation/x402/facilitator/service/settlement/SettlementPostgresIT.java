package org.cardanofoundation.x402.facilitator.service.settlement;

import java.util.ArrayList;
import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
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
@Testcontainers
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
        chain.currentSlot = 999_700L;
        serviceA = service(repoA);
        serviceB = service(repoB);
    }

    SettlementService service(SettlementRepository repo) {
        ExactCardanoScheme scheme = new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier()), 32768,
                ShelleyNetworkClock.forNetwork("cardano:preprod", null));
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
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            SettlementService svc = (i % 2 == 0) ? serviceA : serviceB; // two app "contexts"
            futures.add(pool.submit(() -> {
                start.await();
                SettleResponse r = svc.settle(payload(tx), requirements());
                if (r.success()) successes.incrementAndGet();
                else if ("settlement_pending".equals(r.errorReason())) duplicates.incrementAndGet();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        assertThat(successes.get()).isPositive();
        assertThat(successes.get() + duplicates.get()).isEqualTo(threads);
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void newClaimsPersistSelectedPolicyAndLocalAcceptance() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        SettleResponse response = serviceA.settle(payload(tx), requirements());
        assertThat(response.success()).isTrue();
        Map<String, Object> row = plainA.queryForMap("SELECT selected_confirmations, submission_provenance, "
                + "submission_accepted FROM facilitator.settlement WHERE tx_hash = ?", response.transaction());
        assertThat(row).containsEntry("selected_confirmations", 1)
                .containsEntry("submission_provenance", "LOCAL").containsEntry("submission_accepted", true);
    }

    @Test
    void termsRaceClaimsExactlyOneTransactionAndConflictLeavesNoOrphan() throws Exception {
        String terms = "ab".repeat(32);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        String a = "aa".repeat(32), b = "bb".repeat(32);
        Future<Boolean> fa = pool.submit(() -> { start.await(); return repoA.insertClaim(claim(a, terms)); });
        Future<Boolean> fb = pool.submit(() -> { start.await(); return repoB.insertClaim(claim(b, terms)); });
        start.countDown();
        assertThat(List.of(fa.get(), fb.get())).containsExactlyInAnyOrder(true, false);
        pool.shutdown();
        assertThat(plainA.queryForObject("SELECT count(*) FROM facilitator.settlement", Integer.class)).isEqualTo(1);
        assertThat(repoA.find(a).isPresent() ^ repoA.find(b).isPresent()).isTrue();
    }

    @Test
    void rejectedAndExpiredTermsStayBoundAndOnlyOwnedNoSendClaimIsReleased() {
        for (SettlementRecord.Status terminal : List.of(SettlementRecord.Status.FAILED, SettlementRecord.Status.EXPIRED)) {
            String terms = terminal == SettlementRecord.Status.FAILED ? "aa".repeat(32) : "bb".repeat(32);
            var original = claim(terms, terms);
            assertThat(repoA.insertClaim(original)).isTrue();
            assertThat(repoA.casTransition(original.txHash(), original.attemptId(), SettlementRecord.Status.CLAIMED,
                    terminal, Map.of())).isTrue();
            assertThat(repoB.insertClaim(claim("cc".repeat(32), terms))).isFalse();
            assertThat(repoB.reclaim(claim(terms, terms), Duration.ZERO)).isFalse();
        }
        var noSend = claim("dd".repeat(32), "dd".repeat(32));
        assertThat(repoA.insertClaim(noSend)).isTrue();
        assertThat(repoA.casTransition(noSend.txHash(), noSend.attemptId(), SettlementRecord.Status.CLAIMED,
                SettlementRecord.Status.SUBMITTING, Map.of())).isTrue();
        assertThat(repoB.releaseUnsubmitted(noSend.txHash(), java.util.UUID.randomUUID())).isFalse();
        assertThat(repoB.insertClaim(claim("ee".repeat(32), noSend.termsDigest()))).isFalse();
        assertThat(repoA.releaseUnsubmitted(noSend.txHash(), noSend.attemptId())).isTrue();
        assertThat(repoB.insertClaim(claim("ee".repeat(32), noSend.termsDigest()))).isTrue();
    }

    @Test
    void policyFencePreventsStaleReconcilerPromotionAfterStricterRetry() {
        var initial = claim("ab".repeat(32), null);
        assertThat(repoA.insertClaim(initial)).isTrue();
        assertThat(repoA.casTransition(initial.txHash(), initial.attemptId(), SettlementRecord.Status.CLAIMED,
                SettlementRecord.Status.SUBMITTED, Map.of())).isTrue();
        var snapshot = repoA.find(initial.txHash()).orElseThrow();
        assertThat(repoB.bindVerifiedRetry(snapshot, 5, null)).isTrue();
        assertThat(repoA.recordObservation(snapshot, SettlementRecord.Status.CONFIRMED, Map.of())).isFalse();
        assertThat(repoA.find(initial.txHash()).orElseThrow().status()).isEqualTo(SettlementRecord.Status.SUBMITTED);
    }

    @Test
    void stalePreSubmitOwnerCannotSubmitAfterAnotherContextReclaims() {
        var stale = claim("ac".repeat(32), null);
        assertThat(repoA.insertClaim(stale)).isTrue();
        plainA.update("UPDATE facilitator.settlement SET claimed_at = now() - interval '1 hour' WHERE tx_hash = ?", stale.txHash());
        var fresh = claim(stale.txHash(), null);
        assertThat(repoB.reclaim(fresh, Duration.ofSeconds(2))).isTrue();
        assertThat(repoA.casTransition(stale.txHash(), stale.attemptId(), SettlementRecord.Status.CLAIMED,
                SettlementRecord.Status.SUBMITTING, Map.of())).isFalse();
        assertThat(repoB.casTransition(fresh.txHash(), fresh.attemptId(), SettlementRecord.Status.CLAIMED,
                SettlementRecord.Status.SUBMITTING, Map.of())).isTrue();
        assertThat(repoA.reclaim(claim(stale.txHash(), null), Duration.ZERO)).isFalse();
    }

    private SettlementRecord claim(String hash, String terms) {
        return new SettlementRecord(hash, java.util.UUID.randomUUID(), "d", "cardano:preprod",
                SettlementRecord.Status.CLAIMED, TestTx.PAYER_ADDRESS, TestTx.PAY_TO, "lovelace",
                new java.math.BigDecimal("2000000"), "masumi", TestTx.NONCE, 1000000L,
                java.time.Instant.now(), null, null, null, null, null, null,
                1, SettlementRecord.SubmissionProvenance.LOCAL, false, terms);
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
