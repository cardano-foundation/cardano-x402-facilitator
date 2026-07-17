package org.cardanofoundation.x402.facilitator.service.settlement;

import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ExactCardanoSettleTest {

    static DataSource ds;
    static SettlementRepository repo;
    static NamedParameterJdbcTemplate jdbc;

    FakeChainService chain;
    ExactCardanoScheme scheme;
    SettlementService service;

    @BeforeAll
    static void initDb() {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:settle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        h2.setDriverClassName("org.h2.Driver");
        ds = h2;
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .schemas("facilitator").defaultSchema("facilitator").createSchemas(true).load().migrate();
        jdbc = new NamedParameterJdbcTemplate(ds);
        repo = new SettlementRepository(jdbc);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM facilitator.settlement", Map.of());
        chain = new FakeChainService();
        chain.unspent.put(TestTx.NONCE, TestTx.PAYER_ADDRESS);
        chain.currentSlot = 500_000L;
        scheme = new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier()), 32768);
        service = service(false);
    }

    SettlementService service(boolean idempotentReplay) {
        return new SettlementService(repo, scheme, chain, new CardanoTransactionDecoder(),
                new SettlementService.Config(Duration.ofSeconds(2), 1, false, idempotentReplay,
                        Duration.ofMinutes(10), Duration.ofSeconds(2)),
                Clock.systemUTC());
    }

    PaymentRequirements requirements() {
        return new PaymentRequirements("exact", "cardano:preprod", "lovelace", "2000000",
                TestTx.PAY_TO, 600, Map.of("assetTransferMethod", "default"));
    }

    PaymentPayload payload(String txB64, Map<String, Object> resource) {
        Map<String, Object> p = new HashMap<>();
        p.put("transaction", txB64);
        p.put("nonce", TestTx.NONCE);
        return new PaymentPayload(2, resource, requirements(), p, null);
    }

    PaymentPayload payload(String txB64) {
        return payload(txB64, Map.of("url", "https://example.test/a"));
    }

    @Test
    void happyPathConfirmsAndJournals() {
        SettleResponse r = service.settle(payload(TestTx.buildBase64(TestTx.Spec.defaults())), requirements());
        assertThat(r.success()).isTrue();
        assertThat(r.transaction()).isEqualTo(chain.submittedTxHash);
        assertThat(r.extra()).containsEntry("status", "confirmed");
        SettlementRecord rec = repo.find(chain.submittedTxHash).orElseThrow();
        assertThat(rec.status()).isEqualTo(SettlementRecord.Status.CONFIRMED);
        assertThat(rec.payer()).isEqualTo(TestTx.PAYER_ADDRESS);
        assertThat(rec.responseJson()).contains("confirmed");
    }

    @Test
    void verifyFailureShortCircuitsBeforeSubmit() {
        chain.unspent.clear(); // nonce not on chain
        SettleResponse r = service.settle(payload(TestTx.buildBase64(TestTx.Spec.defaults())), requirements());
        assertThat(r.success()).isFalse();
        assertThat(r.errorReason()).isEqualTo(ErrorCodes.NONCE_NOT_ON_CHAIN);
        assertThat(r.transaction()).isEmpty();
        assertThat(chain.submitCount).isZero();
    }

    @Test
    void duplicateSettleOfConfirmedTxIsRejectedByDefault() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        assertThat(service.settle(payload(tx), requirements()).success()).isTrue();
        SettleResponse dup = service.settle(payload(tx), requirements());
        assertThat(dup.success()).isFalse();
        assertThat(dup.errorReason()).isEqualTo(ErrorCodes.DUPLICATE_SETTLEMENT);
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void concurrentSettlesSubmitExactlyOnce() throws Exception {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                SettleResponse r = service.settle(payload(tx), requirements());
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
    void nodeRejectionReleasesClaimAndRetryCanSucceed() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.submissionResult = new SubmissionResult.Rejected("BadInputsUTxO");
        SettleResponse fail = service.settle(payload(tx), requirements());
        assertThat(fail.errorReason()).isEqualTo(ErrorCodes.SETTLEMENT_FAILED);
        chain.submissionResult = null; // node accepts now
        SettleResponse retry = service.settle(payload(tx), requirements());
        assertThat(retry.success()).isTrue();
        assertThat(chain.submitCount).isEqualTo(2);
    }

    @Test
    void notSubmittedReleasesClaim() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.submissionResult = new SubmissionResult.NotSubmitted("era unresolvable");
        SettleResponse fail = service.settle(payload(tx), requirements());
        assertThat(fail.errorReason()).isEqualTo(ErrorCodes.SETTLEMENT_FAILED);
        String txHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                .getTxHash(java.util.Base64.getDecoder().decode(tx)).toLowerCase();
        assertThat(repo.find(txHash).orElseThrow().status()).isEqualTo(SettlementRecord.Status.FAILED);
    }

    @Test
    void unknownOutcomeKeepsSubmittingAndRetryConverges() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.submissionResult = new SubmissionResult.Unknown("socket timeout");
        SettleResponse first = service.settle(payload(tx), requirements());
        assertThat(first.errorReason()).isEqualTo(ErrorCodes.SETTLEMENT_NOT_CONFIRMED);
        assertThat(first.transaction()).isNotEmpty();
        String txHash = first.transaction();
        assertThat(repo.find(txHash).orElseThrow().status()).isEqualTo(SettlementRecord.Status.SUBMITTING);
        // retry: journal SUBMITTING branch does a one-shot check, promotes at depth,
        // then reports duplicate (replay off) — but the row converges to CONFIRMED
        chain.submissionResult = null;
        SettleResponse retry = service.settle(payload(tx), requirements());
        assertThat(retry.errorReason()).isEqualTo(ErrorCodes.DUPLICATE_SETTLEMENT);
        assertThat(repo.find(txHash).orElseThrow().status()).isEqualTo(SettlementRecord.Status.CONFIRMED);
        assertThat(chain.submitCount).isEqualTo(1); // never rebroadcast
    }

    @Test
    void confirmationTimeoutKeepsRowAndReportsNotConfirmedWithoutStatusClaim() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.includedDepth = 0; // never included
        SettleResponse r = service.settle(payload(tx), requirements());
        assertThat(r.success()).isFalse();
        assertThat(r.errorReason()).isEqualTo(ErrorCodes.SETTLEMENT_NOT_CONFIRMED);
        assertThat(r.extra()).isNull(); // timeout is not mempool evidence
        assertThat(repo.find(r.transaction()).orElseThrow().status())
                .isEqualTo(SettlementRecord.Status.NOT_CONFIRMED);
        SettleResponse dup = service.settle(payload(tx), requirements());
        assertThat(dup.errorReason()).isEqualTo(ErrorCodes.DUPLICATE_SETTLEMENT);
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void staleClaimIsReclaimable() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        String txHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                .getTxHash(java.util.Base64.getDecoder().decode(tx)).toLowerCase();
        // simulate an attempt that died in the claim->submit window, older than the ttl
        repo.insertClaim(new SettlementRecord(txHash, UUID.randomUUID(), "digest", "cardano:preprod",
                SettlementRecord.Status.CLAIMED, null, null, null, null, null, null, null,
                Instant.now().minus(Duration.ofMinutes(5)), null, null, null, null, null, null));
        SettleResponse r = service.settle(payload(tx), requirements());
        assertThat(r.success()).isTrue();
    }

    @Test
    void confirmedTxWithDifferentResourceFallsThroughToVerification() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        assertThat(service.settle(payload(tx, Map.of("url", "https://example.test/a")),
                requirements()).success()).isTrue();
        chain.unspent.clear(); // the nonce is now genuinely spent on-chain
        SettleResponse other = service.settle(payload(tx, Map.of("url", "https://example.test/B")),
                requirements());
        assertThat(other.success()).isFalse();
        assertThat(other.errorReason()).isEqualTo(ErrorCodes.NONCE_NOT_ON_CHAIN); // not duplicate
    }

    @Test
    void idempotentReplayReturnsRecordedSuccessAfterStabilityCheck() {
        SettlementService replaying = service(true);
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        assertThat(replaying.settle(payload(tx), requirements()).success()).isTrue();
        SettleResponse replayed = replaying.settle(payload(tx), requirements());
        assertThat(replayed.success()).isTrue();
        assertThat(replayed.extra()).containsEntry("status", "confirmed");
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void replayDetectsRollbackAndDemotes() {
        SettlementService replaying = service(true);
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        SettleResponse first = replaying.settle(payload(tx), requirements());
        assertThat(first.success()).isTrue();
        chain.includedDepth = 0; // rolled back
        SettleResponse replayed = replaying.settle(payload(tx), requirements());
        assertThat(replayed.success()).isFalse();
        assertThat(replayed.errorReason()).isEqualTo(ErrorCodes.SETTLEMENT_NOT_CONFIRMED);
        assertThat(repo.find(first.transaction()).orElseThrow().status())
                .isEqualTo(SettlementRecord.Status.SUBMITTED);
    }

    @Test
    void duplicateProbeLookupErrorPreservesState() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.includedDepth = 0;
        SettleResponse first = service.settle(payload(tx), requirements()); // -> NOT_CONFIRMED
        assertThat(first.errorReason()).isEqualTo(ErrorCodes.SETTLEMENT_NOT_CONFIRMED);
        chain.throwOnInclusionCheck = true;
        SettleResponse dup = service.settle(payload(tx), requirements());
        assertThat(dup.errorReason()).isEqualTo(ErrorCodes.CHAIN_LOOKUP_FAILED);
        assertThat(repo.find(first.transaction()).orElseThrow().status())
                .isEqualTo(SettlementRecord.Status.NOT_CONFIRMED); // untouched
    }

    @Test
    void casFencingRejectsWrongAttempt() {
        String txHash = "ff".repeat(32);
        UUID owner = UUID.randomUUID();
        repo.insertClaim(new SettlementRecord(txHash, owner, "d", "cardano:preprod",
                SettlementRecord.Status.CLAIMED, null, null, null, null, null, null, null,
                Instant.now(), null, null, null, null, null, null));
        assertThat(repo.casTransition(txHash, UUID.randomUUID(),
                SettlementRecord.Status.CLAIMED, SettlementRecord.Status.SUBMITTING, Map.of())).isFalse();
        assertThat(repo.casTransition(txHash, owner,
                SettlementRecord.Status.CLAIMED, SettlementRecord.Status.SUBMITTING, Map.of())).isTrue();
    }

    @Test
    void expiredRowWithTxActuallyOnChainPromotesOnLookup() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        String txHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                .getTxHash(java.util.Base64.getDecoder().decode(tx)).toLowerCase();
        UUID attempt = UUID.randomUUID();
        repo.insertClaim(new SettlementRecord(txHash, attempt, "d", "cardano:preprod",
                SettlementRecord.Status.CLAIMED, TestTx.PAYER_ADDRESS, null, null, null, null,
                TestTx.NONCE, null, Instant.now(), null, null, null, null, null, null));
        repo.casTransition(txHash, attempt, SettlementRecord.Status.CLAIMED,
                SettlementRecord.Status.SUBMITTING, Map.of());
        repo.casTransition(txHash, attempt, SettlementRecord.Status.SUBMITTING,
                SettlementRecord.Status.SUBMITTED, Map.of());
        repo.casTransition(txHash, attempt, SettlementRecord.Status.SUBMITTED,
                SettlementRecord.Status.EXPIRED, Map.of());
        chain.includedDepth = 3; // it landed after all
        SettleResponse r = service.settle(payload(tx), requirements());
        assertThat(r.errorReason()).isEqualTo(ErrorCodes.DUPLICATE_SETTLEMENT); // promoted, replay off
        assertThat(repo.find(txHash).orElseThrow().status()).isEqualTo(SettlementRecord.Status.CONFIRMED);
    }

    // Reconciler paths

    @Test
    void reconcilerPromotesExpiresAndDemotes() {
        SettlementReconciler reconciler = new SettlementReconciler(repo,
                Map.of("cardano:preprod", chain), null, 1,
                Duration.ofMinutes(10), Duration.ofHours(24), Clock.systemUTC(), false);

        // a SUBMITTED row whose tx is on-chain -> promote
        String h1 = "aa".repeat(32);
        seedRow(h1, SettlementRecord.Status.SUBMITTED, 900_000L, Instant.now());
        // a SUBMITTED row whose tx ttl passed, never included -> expire
        String h2 = "bb".repeat(32);
        seedRow(h2, SettlementRecord.Status.SUBMITTED, 100L, Instant.now());
        // a TTL-less row older than the horizon, never included -> expire
        String h3 = "cc".repeat(32);
        seedRow(h3, SettlementRecord.Status.SUBMITTING, null,
                Instant.now().minus(Duration.ofHours(30)));

        chain.inclusionDepthByHash.put(h1, 2);
        chain.inclusionDepthByHash.put(h2, 0);
        chain.inclusionDepthByHash.put(h3, 0);
        chain.currentSlot = 10_000L; // > h2 ttl 100 + margin

        reconciler.sweep();
        assertThat(repo.find(h1).orElseThrow().status()).isEqualTo(SettlementRecord.Status.CONFIRMED);
        assertThat(repo.find(h2).orElseThrow().status()).isEqualTo(SettlementRecord.Status.EXPIRED);
        assertThat(repo.find(h3).orElseThrow().status()).isEqualTo(SettlementRecord.Status.EXPIRED);

        // rollback: recent CONFIRMED not on chain anymore -> demote
        chain.inclusionDepthByHash.put(h1, 0);
        reconciler.sweep();
        assertThat(repo.find(h1).orElseThrow().status()).isEqualTo(SettlementRecord.Status.SUBMITTED);
    }

    private UUID seedRow(String txHash, SettlementRecord.Status target, Long ttlSlot, Instant claimedAt) {
        UUID attempt = UUID.randomUUID();
        repo.insertClaim(new SettlementRecord(txHash, attempt, "d", "cardano:preprod",
                SettlementRecord.Status.CLAIMED, TestTx.PAYER_ADDRESS, null, null, null, null,
                TestTx.NONCE, ttlSlot, claimedAt, null, null, null, null, null, null));
        if (target != SettlementRecord.Status.CLAIMED) {
            repo.casTransition(txHash, attempt, SettlementRecord.Status.CLAIMED,
                    SettlementRecord.Status.SUBMITTING, Map.of());
            if (target != SettlementRecord.Status.SUBMITTING) {
                repo.casTransition(txHash, attempt, SettlementRecord.Status.SUBMITTING, target, Map.of());
            }
        }
        // keep claimed_at as provided for horizon tests
        jdbc.update("UPDATE facilitator.settlement SET claimed_at = :t WHERE tx_hash = :h",
                Map.of("t", Timestamp.from(claimedAt), "h", txHash));
        return attempt;
    }
}
