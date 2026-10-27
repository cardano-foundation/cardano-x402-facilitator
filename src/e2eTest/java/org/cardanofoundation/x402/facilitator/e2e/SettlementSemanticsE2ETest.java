package org.cardanofoundation.x402.facilitator.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.Poll;
import org.cardanofoundation.x402.facilitator.e2e.kit.SchemaAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.ShimClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How /settle answers, by confirmation policy and instance, and how it treats a
 * payment it has seen before: idempotent re-settle, a rebound duplicate, a spent
 * nonce, and two concurrent settles that must broadcast once.
 *
 * Each test pays from its own fresh wallet: payments built from one wallet
 * before the first settles would share an input (see PaymentFactory.Spec#build).
 */
@StackRequired
class SettlementSemanticsE2ETest {

    private static final Duration CONVERGE_DEADLINE = Duration.ofSeconds(120);

    private static DevnetKit kit;
    private static PaymentFactory payments;
    private static FacilitatorClient facilitator;
    private static FacilitatorClient mempoolFacilitator;
    private static ShimClient shim;
    private static Wallet receiver;

    @BeforeAll
    static void stack() {
        kit = DevnetKit.fromEnv();
        payments = new PaymentFactory(kit);
        facilitator = FacilitatorClient.defaultInstance();
        mempoolFacilitator = FacilitatorClient.mempoolInstance();
        shim = ShimClient.fromEnv();
        receiver = kit.newWallet(5);
    }

    // ---- confirmation policy, default instance (accept-mempool false) ----

    // 10 is what separates "honours n" from "ignores the policy": the facilitator polls
    // inclusion every 3 s on a 1 block/s devnet, so a first sighting already shows a
    // depth of ~2-4. 0 cannot be told apart from 1 once included; it is kept for the path.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 10})
    void aPolicyOfNConfirmsWithAtLeastNConfirmations(int confirmations) {
        Payment payment = withPolicy(freshPayment(), confirmations);
        JsonNode settled = settleSuccessfully(facilitator, payment);
        assertThat(settled.path("extra").path("status").asText()).isEqualTo("confirmed");
        assertThat(settled.path("extra").path("confirmations").asInt()).isGreaterThanOrEqualTo(confirmations);
        kit.assertOnChain(payment.txHash());
    }

    @Test
    void anAbsentPolicyMeansOneConfirmation() {
        Payment payment = freshPayment();
        assertThat(extraOf(payment)).doesNotContainKey("confirmationPolicy");
        JsonNode settled = settleSuccessfully(facilitator, payment);
        assertThat(settled.path("extra").path("status").asText()).isEqualTo("confirmed");
        assertThat(settled.path("extra").path("confirmations").asInt()).isGreaterThanOrEqualTo(1);
    }

    // Yano's /mempool/{hash} never reports a pending tx, so the facilitator only ever sees
    // NotSeen -> Included here. A provider that DOES report mempool status takes a different
    // branch (SettlementService awaits depth -1 raw, then answers settlement_pending): this
    // test cannot exercise that branch. Recorded in WP-3's Log as a finding.
    @Test
    void mempoolPolicyStillWaitsForInclusionWhenMempoolAcceptanceIsOff() {
        Payment payment = withPolicy(freshPayment(), -1);
        JsonNode settled = settleSuccessfully(facilitator, payment);
        assertThat(settled.path("extra").path("status").asText()).isEqualTo("confirmed");
        assertThat(settled.path("extra").path("confirmations").asInt()).isGreaterThanOrEqualTo(0);
        // It answered only after inclusion, so the block is already visible.
        assertThat(kit.isOnChain(payment.txHash())).isTrue();
    }

    // ---- confirmation policy, mempool instance (accept-mempool true) ----

    @Test
    void mempoolPolicyAnswersAtMempoolAcceptanceWhenEnabled() {
        Payment payment = withPolicy(freshPayment(), -1);
        JsonNode settled = settleSuccessfully(mempoolFacilitator, payment);
        assertThat(settled.path("extra").path("status").asText()).isEqualTo("mempool");
        assertThat(settled.path("extra").path("confirmations").asInt()).isEqualTo(-1);
        assertThat(settled.path("extra").path("transactionId").asText()).isEqualTo(payment.txHash());
        // Mempool acceptance is a promise the chain then keeps.
        kit.assertOnChain(payment.txHash());
    }

    @Test
    void theMempoolInstanceStillConfirmsAnInclusionPolicy() {
        Payment payment = withPolicy(freshPayment(), 1);
        JsonNode settled = settleSuccessfully(mempoolFacilitator, payment);
        assertThat(settled.path("extra").path("status").asText()).isEqualTo("confirmed");
        assertThat(settled.path("extra").path("confirmations").asInt()).isGreaterThanOrEqualTo(1);
    }

    // ---- a payment seen before ----

    @Test
    void reSettlingTheSamePayloadSucceedsAgainWithoutRebroadcasting() {
        Payment payment = freshPayment();
        settleSuccessfully(facilitator, payment);

        JsonNode again = settleSuccessfully(facilitator, payment);
        assertThat(again.path("transaction").asText()).isEqualTo(payment.txHash());
        assertThat(again.path("extra").path("status").asText()).isEqualTo("confirmed");
        assertThat(shim.submitCount(payment.txBytes())).isEqualTo(1);
    }

    @Test
    void theSameTransactionUnderDifferentRequirementsIsADuplicate() {
        Payment payment = freshPayment();
        settleSuccessfully(facilitator, payment);

        Payment rebound = payment.withRequirement("maxTimeoutSeconds", PaymentFactory.DEFAULT_MAX_TIMEOUT_SECONDS + 300);
        FacilitatorClient.Response response = facilitator.settle(rebound.body());
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        JsonNode body = SchemaAssert.assertMatches(SchemaAssert.SETTLE, response);
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("errorReason").asText()).isEqualTo("duplicate_settlement");
        assertThat(body.path("transaction").asText()).isEmpty();
        assertThat(shim.submitCount(payment.txBytes())).isEqualTo(1);
    }

    @Test
    void verifyingASettledPaymentReportsItsNonceSpent() {
        Payment payment = freshPayment();
        settleSuccessfully(facilitator, payment);

        FacilitatorClient.Response response = facilitator.verify(payment.body());
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = SchemaAssert.assertMatches(SchemaAssert.VERIFY, response);
        assertThat(body.path("isValid").asBoolean()).isFalse();
        assertThat(body.path("invalidReason").asText()).isEqualTo("invalid_exact_cardano_payload_nonce_not_on_chain");
    }

    @Test
    void twoConcurrentSettlesBroadcastOnceAndBothSucceed() throws Exception {
        // Its own fresh tx: the shim counter is shared by everything that runs.
        Payment payment = freshPayment();
        AtomicInteger pendingAnswers = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<JsonNode>> racers = List.of(
                    pool.submit(() -> { start.await(); return settleUntilFinal(facilitator, payment, pendingAnswers); }),
                    pool.submit(() -> { start.await(); return settleUntilFinal(facilitator, payment, pendingAnswers); }));
            start.countDown();
            for (Future<JsonNode> racer : racers) {
                JsonNode settled = racer.get();
                assertThat(settled.path("success").asBoolean()).as("%s", settled).isTrue();
                assertThat(settled.path("transaction").asText()).isEqualTo(payment.txHash());
            }
        } finally {
            pool.shutdownNow();
        }
        // The race really happened: the loser saw the winner's in-flight claim at least once
        // (the winner holds it for the 3 s+ it spends awaiting inclusion).
        assertThat(pendingAnswers.get()).as("settlement_pending answers seen").isGreaterThanOrEqualTo(1);
        assertThat(shim.submitCount(payment.txBytes())).isEqualTo(1);
        kit.assertOnChain(payment.txHash());
    }

    // ---- helpers ----

    private static Payment freshPayment() {
        return payments.from(kit.newWallet(10)).payTo(receiver).lovelace(2_000_000).build();
    }

    private static Payment withPolicy(Payment payment, int confirmations) {
        return payment.withExtra("confirmationPolicy", Map.of("l1Confirmations", confirmations));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extraOf(Payment payment) {
        return (Map<String, Object>) payment.requirements().get("extra");
    }

    private static JsonNode settleSuccessfully(FacilitatorClient instance, Payment payment) {
        FacilitatorClient.Response response = instance.settle(payment.body());
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        JsonNode body = SchemaAssert.assertMatches(SchemaAssert.SETTLE, response);
        assertThat(body.path("success").asBoolean()).as("%s", response).isTrue();
        assertThat(body.path("transaction").asText()).isEqualTo(payment.txHash());
        assertThat(body.path("payer").asText()).isEqualTo(payment.payer().address());
        return body;
    }

    /**
     * Settles, retrying while the answer is settlement_pending (the loser of a
     * race sees the winner's in-flight claim), until a final answer or the deadline.
     */
    private static JsonNode settleUntilFinal(FacilitatorClient instance, Payment payment, AtomicInteger pendingAnswers) {
        return Poll.until("a final settle answer for " + payment.txHash(), CONVERGE_DEADLINE, () -> {
            FacilitatorClient.Response response = instance.settle(payment.body());
            if (response.status() != 200) throw new AssertionError("settle: " + response);
            JsonNode body = SchemaAssert.assertMatches(SchemaAssert.SETTLE, response);
            if (!"settlement_pending".equals(body.path("errorReason").asText())) return Optional.of(body);
            pendingAnswers.incrementAndGet();
            return Optional.empty();
        });
    }
}
