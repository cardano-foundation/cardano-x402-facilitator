package org.cardanofoundation.x402.facilitator.service.settlement;

import java.util.Base64;
import java.util.ArrayList;
import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
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
        chain.currentSlot = 999_700L;
        scheme = new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier()), 32768,
                ShelleyNetworkClock.forNetwork("cardano:preprod", null));
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
    void confirmedRetrySucceedsWithoutRebroadcastEvenWhenReplaySwitchIsFalse() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        assertThat(service.settle(payload(tx), requirements()).success()).isTrue();
        SettleResponse dup = service.settle(payload(tx), requirements());
        assertThat(dup.success()).isTrue();
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
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                SettleResponse r = service.settle(payload(tx), requirements());
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
    void definitiveRejectionKeepsTombstoneAndNeverBroadcastsAgain() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.submissionResult = new SubmissionResult.Rejected("BadInputsUTxO");
        SettleResponse fail = service.settle(payload(tx), requirements());
        assertThat(fail.errorReason()).isEqualTo("exact_cardano_settlement_definitively_rejected");
        chain.submissionResult = null; // node accepts now
        SettleResponse retry = service.settle(payload(tx), requirements());
        assertThat(retry.success()).isFalse();
        assertThat(retry.errorReason()).isEqualTo("exact_cardano_settlement_definitively_rejected");
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void notSubmittedReleasesClaim() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.submissionResult = new SubmissionResult.NotSubmitted("era unresolvable");
        SettleResponse fail = service.settle(payload(tx), requirements());
        assertThat(fail.errorReason()).isEqualTo(ErrorCodes.SETTLEMENT_FAILED);
        String txHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                .getTxHash(Base64.getDecoder().decode(tx)).toLowerCase();
        assertThat(repo.find(txHash)).isEmpty();
        chain.submissionResult = null;
        assertThat(service.settle(payload(tx), requirements()).success()).isTrue();
        assertThat(chain.submitCount).isEqualTo(2);
    }

    @Test
    void unknownOutcomeKeepsSubmittingAndRetryConverges() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.submissionResult = new SubmissionResult.Unknown("socket timeout");
        SettleResponse first = service.settle(payload(tx), requirements());
        assertThat(first.errorReason()).isEqualTo("settlement_pending");
        assertThat(first.transaction()).isNotEmpty();
        String txHash = first.transaction();
        assertThat(repo.find(txHash).orElseThrow().status()).isEqualTo(SettlementRecord.Status.SUBMITTING);
        // Retry observes sufficient canonical evidence and succeeds without a second broadcast.
        chain.submissionResult = null;
        SettleResponse retry = service.settle(payload(tx), requirements());
        assertThat(retry.success()).isTrue();
        assertThat(repo.find(txHash).orElseThrow().status()).isEqualTo(SettlementRecord.Status.CONFIRMED);
        assertThat(chain.submitCount).isEqualTo(1); // never rebroadcast
    }

    @Test
    void confirmationTimeoutReturnsProtocolPendingAndRetriesConverge() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.includedDepth = FakeChainService.NOT_SEEN; // never included
        SettleResponse r = service.settle(payload(tx), requirements());
        assertThat(r.success()).isFalse();
        assertThat(r.errorReason()).isEqualTo("settlement_pending");
        assertThat(r.extra()).containsEntry("status", "pending").containsEntry("transactionId", r.transaction());
        assertThat(r.payer()).isEqualTo(TestTx.PAYER_ADDRESS);
        assertThat(repo.find(r.transaction()).orElseThrow().status())
                .isEqualTo(SettlementRecord.Status.NOT_CONFIRMED);
        SettleResponse dup = service.settle(payload(tx), requirements());
        assertThat(dup.errorReason()).isEqualTo("settlement_pending");
        chain.includedDepth = 2;
        chain.unspent.clear();
        assertThat(service.settle(payload(tx), requirements()).success()).isTrue();
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void staleClaimIsReclaimable() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        String txHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                .getTxHash(Base64.getDecoder().decode(tx)).toLowerCase();
        // simulate an attempt that died in the claim->submit window, older than the ttl
        repo.insertClaim(new SettlementRecord(txHash, UUID.randomUUID(), "digest", "cardano:preprod",
                SettlementRecord.Status.CLAIMED, null, null, null, null, null, null, null,
                Instant.now().minus(Duration.ofMinutes(5)), null, null, null, null, null, null,
                1, SettlementRecord.SubmissionProvenance.LOCAL, false, null));
        SettleResponse r = service.settle(payload(tx), requirements());
        assertThat(r.success()).isTrue();
    }

    @Test
    void differentResourceCannotReuseConfirmedPayment() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        assertThat(service.settle(payload(tx, Map.of("url", "https://example.test/a")),
                requirements()).success()).isTrue();
        chain.unspent.clear(); // the nonce is now genuinely spent on-chain
        SettleResponse other = service.settle(payload(tx, Map.of("url", "https://example.test/B")),
                requirements());
        assertThat(other.success()).isFalse();
        assertThat(other.errorReason()).isEqualTo(ErrorCodes.DUPLICATE_SETTLEMENT);
    }

    @Test
    void replaySwitchStillAllowsFreshEvidenceRetry() {
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
        chain.includedDepth = FakeChainService.NOT_SEEN; // rolled back
        SettleResponse replayed = replaying.settle(payload(tx), requirements());
        assertThat(replayed.success()).isFalse();
        assertThat(replayed.errorReason()).isEqualTo("settlement_pending");
        assertThat(repo.find(first.transaction()).orElseThrow().status())
                .isEqualTo(SettlementRecord.Status.SUBMITTED);
    }

    @Test
    void unknownAbsenceCannotDemoteConfirmedPaymentOnRetry() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        SettleResponse first = service.settle(payload(tx), requirements());
        assertThat(first.success()).isTrue();
        chain.includedDepth = FakeChainService.NOT_SEEN;
        chain.observedTipSlot = -1L;
        chain.currentSlot = 2_000_000;
        SettleResponse retry = service.settle(payload(tx), requirements());
        assertThat(retry.errorReason()).isEqualTo("settlement_pending");
        assertThat(repo.find(first.transaction()).orElseThrow().status()).isEqualTo(SettlementRecord.Status.CONFIRMED);
        assertThat(chain.submitCount).isEqualTo(1);
        chain.includedDepth = 3;
        assertThat(service.settle(payload(tx), requirements()).success()).isTrue();
    }

    @Test
    void duplicateProbeLookupErrorPreservesState() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.includedDepth = FakeChainService.NOT_SEEN;
        SettleResponse first = service.settle(payload(tx), requirements()); // -> NOT_CONFIRMED
        assertThat(first.errorReason()).isEqualTo("settlement_pending");
        chain.throwOnInclusionCheck = true;
        SettleResponse dup = service.settle(payload(tx), requirements());
        assertThat(dup.errorReason()).isEqualTo("settlement_pending");
        assertThat(repo.find(first.transaction()).orElseThrow().status())
                .isEqualTo(SettlementRecord.Status.NOT_CONFIRMED); // untouched
    }

    @Test
    void casFencingRejectsWrongAttempt() {
        String txHash = "ff".repeat(32);
        UUID owner = UUID.randomUUID();
        repo.insertClaim(new SettlementRecord(txHash, owner, "d", "cardano:preprod",
                SettlementRecord.Status.CLAIMED, null, null, null, null, null, null, null,
                Instant.now(), null, null, null, null, null, null,
                1, SettlementRecord.SubmissionProvenance.LOCAL, false, null));
        assertThat(repo.casTransition(txHash, UUID.randomUUID(),
                SettlementRecord.Status.CLAIMED, SettlementRecord.Status.SUBMITTING, Map.of())).isFalse();
        assertThat(repo.casTransition(txHash, owner,
                SettlementRecord.Status.CLAIMED, SettlementRecord.Status.SUBMITTING, Map.of())).isTrue();
    }

    @Test
    void expiredRowWithTxActuallyOnChainPromotesOnLookup() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        String txHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                .getTxHash(Base64.getDecoder().decode(tx)).toLowerCase();
        UUID attempt = UUID.randomUUID();
        repo.insertClaim(new SettlementRecord(txHash, attempt, SettlementDigest.compute(requirements(), payload(tx).resource()), "cardano:preprod",
                SettlementRecord.Status.CLAIMED, TestTx.PAYER_ADDRESS, null, null, null, null,
                TestTx.NONCE, null, Instant.now(), null, null, null, null, null, null,
                1, SettlementRecord.SubmissionProvenance.LOCAL, false, null));
        repo.casTransition(txHash, attempt, SettlementRecord.Status.CLAIMED,
                SettlementRecord.Status.SUBMITTING, Map.of());
        repo.casTransition(txHash, attempt, SettlementRecord.Status.SUBMITTING,
                SettlementRecord.Status.SUBMITTED, Map.of());
        repo.casTransition(txHash, attempt, SettlementRecord.Status.SUBMITTED,
                SettlementRecord.Status.EXPIRED, Map.of());
        chain.includedDepth = 3; // it landed after all
        SettleResponse r = service.settle(payload(tx), requirements());
        assertThat(r.success()).isTrue();
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
        // a TTL-less row remains uncertain regardless of age
        String h3 = "cc".repeat(32);
        seedRow(h3, SettlementRecord.Status.SUBMITTING, null,
                Instant.now().minus(Duration.ofHours(30)));

        chain.inclusionDepthByHash.put(h1, 2);
        chain.inclusionDepthByHash.put(h2, FakeChainService.NOT_SEEN);
        chain.inclusionDepthByHash.put(h3, FakeChainService.NOT_SEEN);
        chain.currentSlot = 10_000L; // > h2 ttl 100 + margin

        reconciler.sweep();
        assertThat(repo.find(h1).orElseThrow().status()).isEqualTo(SettlementRecord.Status.CONFIRMED);
        assertThat(repo.find(h2).orElseThrow().status()).isEqualTo(SettlementRecord.Status.EXPIRED);
        assertThat(repo.find(h3).orElseThrow().status()).isEqualTo(SettlementRecord.Status.SUBMITTING);

        // rollback: recent CONFIRMED not on chain anymore -> demote
        chain.inclusionDepthByHash.put(h1, FakeChainService.NOT_SEEN);
        reconciler.sweep();
        assertThat(repo.find(h1).orElseThrow().status()).isEqualTo(SettlementRecord.Status.SUBMITTED);
    }

    @Test
    void reconcilerPreservesUnobservedRowsAndConfirmedRowsWithoutIndexCoverage() {
        String confirmed = "dd".repeat(32);
        String unobserved = "ee".repeat(32);
        seedRow(confirmed, SettlementRecord.Status.SUBMITTED, 1_000_000L, Instant.now());
        seedRow(unobserved, SettlementRecord.Status.SUBMITTED, 100L, Instant.now());
        chain.inclusionDepthByHash.put(confirmed, 2);
        chain.inclusionDepthByHash.put(unobserved, FakeChainService.NOT_SEEN);
        chain.observedTipSlot = -1L;
        SettlementReconciler reconciler = new SettlementReconciler(repo,
                Map.of("cardano:preprod", chain), null, 1,
                Duration.ofMinutes(10), Duration.ofHours(24), Clock.systemUTC(), false);
        reconciler.sweep();
        assertThat(repo.find(confirmed).orElseThrow().status()).isEqualTo(SettlementRecord.Status.CONFIRMED);
        chain.inclusionDepthByHash.put(confirmed, FakeChainService.NOT_SEEN);
        chain.currentSlot = 2_000_000;
        reconciler.sweep();
        assertThat(repo.find(confirmed).orElseThrow().status()).isEqualTo(SettlementRecord.Status.CONFIRMED);
        assertThat(repo.find(unobserved).orElseThrow().status()).isEqualTo(SettlementRecord.Status.SUBMITTED);
        chain.inclusionDepthByHash.put(unobserved, 2);
        reconciler.sweep();
        assertThat(repo.find(unobserved).orElseThrow().status()).isEqualTo(SettlementRecord.Status.CONFIRMED);
    }

    @Test
    void retryRevalidatesRecipientAndAmountAfterInputsAreSpent() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        assertThat(service.settle(payload(tx), requirements()).success()).isTrue();
        chain.unspent.clear();
        PaymentRequirements wrong = new PaymentRequirements("exact", "cardano:preprod", "lovelace",
                "999999999", TestTx.PAY_TO, 600, requirements().extra());
        PaymentPayload attempt = new PaymentPayload(2, payload(tx).resource(), wrong, payload(tx).payload(), null);
        assertThat(service.settle(attempt, wrong).success()).isFalse();
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void stricterRetryWaitsForRequestedDepthAndReportsActualDepth() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        assertThat(service.settle(payload(tx), requirements()).success()).isTrue();
        chain.unspent.clear();
        PaymentRequirements deeper = policy(3);
        PaymentPayload attempt = new PaymentPayload(2, payload(tx).resource(), deeper, payload(tx).payload(), null);
        assertThat(service.settle(attempt, deeper).errorReason()).isEqualTo("settlement_pending");
        chain.includedDepth = 4;
        SettleResponse success = service.settle(attempt, deeper);
        assertThat(success.success()).isTrue();
        assertThat(success.extra()).containsEntry("confirmations", 4);
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void ownAcceptanceImmediatelySettlesOptedInMinusOnePolicy() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        PaymentRequirements req = policy(-1);
        chain.includedDepth = FakeChainService.NOT_SEEN;
        SettlementService optedIn = new SettlementService(repo, scheme, chain, new CardanoTransactionDecoder(),
                new SettlementService.Config(Duration.ofSeconds(2), 1, true, false,
                        Duration.ofMinutes(10), Duration.ofSeconds(2)), Clock.systemUTC());
        SettleResponse result = optedIn.settle(new PaymentPayload(2, payload(tx).resource(), req,
                payload(tx).payload(), null), req);
        assertThat(result.success()).isTrue();
        assertThat(result.extra()).containsEntry("status", "mempool");
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void mismatchedSubmissionHashIsUncertainAndCannotGrantMempoolSuccess() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        PaymentRequirements req = policy(-1);
        chain.includedDepth = FakeChainService.NOT_SEEN;
        chain.submissionResult = new SubmissionResult.Accepted("ff".repeat(32));
        SettlementService optedIn = new SettlementService(repo, scheme, chain, new CardanoTransactionDecoder(),
                new SettlementService.Config(Duration.ofSeconds(2), 1, true, false,
                        Duration.ofMinutes(10), Duration.ofSeconds(2)), Clock.systemUTC());
        PaymentPayload attempt = new PaymentPayload(2, payload(tx).resource(), req, payload(tx).payload(), null);
        SettleResponse result = optedIn.settle(attempt, req);
        assertThat(result.success()).isFalse();
        assertThat(result.errorReason()).isEqualTo("settlement_pending");
        assertThat(result.transaction()).isNotEqualTo("ff".repeat(32));
        assertThat(optedIn.settle(attempt, req).success()).isFalse();
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void includedBelowSelectedDepthNeverExpiresEvenAfterTtl() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        PaymentRequirements req = policy(5);
        SettleResponse first = service.settle(new PaymentPayload(2, payload(tx).resource(), req,
                payload(tx).payload(), null), req);
        assertThat(first.success()).isFalse();
        assertThat(first.extra()).containsEntry("confirmations", 1);
        chain.currentSlot = 2_000_000;
        new SettlementReconciler(repo, Map.of("cardano:preprod", chain), null, 1,
                Duration.ofMinutes(10), Duration.ofHours(24), Clock.systemUTC(), false).sweep();
        assertThat(repo.find(first.transaction()).orElseThrow().status())
                .isEqualTo(SettlementRecord.Status.NOT_CONFIRMED);
    }

    @Test
    void reconcilerDoesNotExtendConfirmationStabilityWindowOnEverySweep() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        SettleResponse first = service.settle(payload(tx), requirements());
        Instant initiallyConfirmed = repo.find(first.transaction()).orElseThrow().confirmedAt();
        Clock later = Clock.fixed(initiallyConfirmed.plusSeconds(60), java.time.ZoneOffset.UTC);
        new SettlementReconciler(repo, Map.of("cardano:preprod", chain), null, 1,
                Duration.ofMinutes(10), Duration.ofHours(24), later, false).sweep();
        assertThat(repo.find(first.transaction()).orElseThrow().confirmedAt()).isEqualTo(initiallyConfirmed);
    }

    @Test
    void resumedUnobservedPaymentExpiresOnlyAfterTtlAndIndexingGraceWithoutRebroadcast() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.includedDepth = FakeChainService.NOT_SEEN;
        SettleResponse first = service.settle(payload(tx), requirements());
        chain.currentSlot = 1_000_120;
        assertThat(service.settle(payload(tx), requirements()).errorReason()).isEqualTo("settlement_pending");
        chain.currentSlot++;
        SettleResponse expired = service.settle(payload(tx), requirements());
        assertThat(expired.success()).isFalse();
        assertThat(expired.errorReason()).isEqualTo("exact_cardano_settlement_failed");
        assertThat(expired.extra()).containsEntry("status", "expired");
        assertThat(repo.find(first.transaction()).orElseThrow().status()).isEqualTo(SettlementRecord.Status.EXPIRED);
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void wallClockPastTtlCannotExpireWhileProviderTipIsBehindTtl() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.includedDepth = FakeChainService.NOT_SEEN;
        SettleResponse first = service.settle(payload(tx), requirements());
        chain.currentSlot = 1_000_400;
        chain.observedTipSlot = 1_000_050L;
        assertThat(service.settle(payload(tx), requirements()).errorReason()).isEqualTo("settlement_pending");
        assertThat(repo.find(first.transaction()).orElseThrow().status())
                .isEqualTo(SettlementRecord.Status.NOT_CONFIRMED);
        chain.observedTipSlot = 1_000_121L;
        assertThat(service.settle(payload(tx), requirements()).extra()).containsEntry("status", "expired");
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void unknownIndexCoverageNeverExpiresOrRebroadcastsAfterTtl() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.includedDepth = FakeChainService.NOT_SEEN;
        SettleResponse first = service.settle(payload(tx), requirements());
        chain.currentSlot = 2_000_000;
        chain.observedTipSlot = -1L;
        for (int retry = 0; retry < 3; retry++) {
            assertThat(service.settle(payload(tx), requirements()).errorReason()).isEqualTo("settlement_pending");
        }
        assertThat(repo.find(first.transaction()).orElseThrow().status()).isEqualTo(SettlementRecord.Status.NOT_CONFIRMED);
        assertThat(chain.submitCount).isEqualTo(1);
        chain.includedDepth = 3;
        assertThat(service.settle(payload(tx), requirements()).success()).isTrue();
    }

    @Test
    void inclusionLookupFailurePastTtlNeverBecomesExpiry() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        chain.includedDepth = FakeChainService.NOT_SEEN;
        SettleResponse first = service.settle(payload(tx), requirements());
        chain.currentSlot = 2_000_000;
        chain.throwOnInclusionCheck = true;
        assertThat(service.settle(payload(tx), requirements()).errorReason()).isEqualTo("settlement_pending");
        assertThat(repo.find(first.transaction()).orElseThrow().status()).isEqualTo(SettlementRecord.Status.NOT_CONFIRMED);
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void stricterRetryDuringSubmissionFencesImmediateMempoolSuccess() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch finishSend = new CountDownLatch(1);
        chain = new FakeChainService() {
            @Override public SubmissionResult submitTransaction(byte[] bytes) {
                sending.countDown();
                try { finishSend.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return super.submitTransaction(bytes);
            }
        };
        chain.unspent.put(TestTx.NONCE, TestTx.PAYER_ADDRESS);
        chain.includedDepth = FakeChainService.NOT_SEEN;
        scheme = new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier()), 32768, ShelleyNetworkClock.forNetwork("cardano:preprod", null));
        var optedIn = new SettlementService(repo, scheme, chain, new CardanoTransactionDecoder(),
                new SettlementService.Config(Duration.ofSeconds(2), 1, true, false,
                        Duration.ofMinutes(10), Duration.ofSeconds(2)), Clock.systemUTC());
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        var fast = new PaymentPayload(2, payload(tx).resource(), policy(-1), payload(tx).payload(), null);
        var strict = new PaymentPayload(2, payload(tx).resource(), policy(3), payload(tx).payload(), null);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<SettleResponse> first = pool.submit(() -> optedIn.settle(fast, policy(-1)));
            assertThat(sending.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(optedIn.settle(strict, policy(3)).errorReason()).isEqualTo("settlement_pending");
            } finally {
                finishSend.countDown();
            }
            assertThat(first.get().errorReason()).isEqualTo("settlement_pending");
        }
        assertThat(chain.submitCount).isEqualTo(1);
    }

    private PaymentRequirements policy(int depth) {
        return new PaymentRequirements("exact", "cardano:preprod", "lovelace", "2000000",
                TestTx.PAY_TO, 600, Map.of("assetTransferMethod", "default",
                "confirmationPolicy", Map.of("l1Confirmations", depth)));
    }

    private UUID seedRow(String txHash, SettlementRecord.Status target, Long ttlSlot, Instant claimedAt) {
        UUID attempt = UUID.randomUUID();
        repo.insertClaim(new SettlementRecord(txHash, attempt, "d", "cardano:preprod",
                SettlementRecord.Status.CLAIMED, TestTx.PAYER_ADDRESS, null, null, null, null,
                TestTx.NONCE, ttlSlot, claimedAt, null, null, null, null, null, null,
                1, SettlementRecord.SubmissionProvenance.LOCAL, false, null));
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
