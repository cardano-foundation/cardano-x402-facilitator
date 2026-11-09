package org.cardanofoundation.x402.facilitator.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Funding;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.Poll;
import org.cardanofoundation.x402.facilitator.e2e.kit.SchemaAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.ShimClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.TransferAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The facilitator's provider-failure paths, produced by the shim's opt-in faults instead of a
 * broken chain: a submit whose answer is lost, a ledger rejection, a swallowed submit, a UTxO
 * without a coin value, a hidden transaction, a rate-limited lookup.
 *
 * <p>{@code @Isolated}: the faults are path-wide (the 429 case hits a whole funding tx's UTxO
 * lookup). Every test clears them in {@code finally}, and IsolatedStackGuard once more afterwards.
 *
 * <p>Each case cites the code that must answer; a disagreement is a finding, never a reason to
 * loosen the assertion.
 *
 * <p>Known, deliberately NOT pinned: a swallowed submit leaves a row that can never expire. With a
 * Blockfrost-compatible provider NotSeen carries no watermark (observedThroughSlot = -1,
 * InclusionResult.java:9), so neither SettlementService#expireIfProven nor the reconciler can
 * prove expiry. That row stays due and both reconcilers poll it every 30 s.
 */
@Isolated
@StackRequired
class ProviderFaultE2ETest {

    private static final Duration CONVERGE_DEADLINE = Duration.ofSeconds(120);

    /**
     * A cardano-node ledger verdict as Blockfrost relays it on POST /tx/submit (HTTP 400): the
     * ApplyTxError / BadInputsUTxO shape of node 10.x in the Conway era. The facilitator
     * classifies it by its text (BlockfrostChainService.java:239-243).
     */
    private static final String LEDGER_REJECTION = """
            {"error":"Bad Request","message":"\\"transaction submit error ShelleyTxValidationError ShelleyBasedEraConway \
            (ApplyTxError (ConwayUtxowFailure (UtxoFailure (BadInputsUTxO (fromList [TxIn (TxId {unTxId = SafeHash \
            \\\\\\"0d4f2b8e6c6a4b3f8e9d1c0a7b5e3f2d4c6a8b0e1f3d5c7a9b1d3f5e7a9c1b3d\\\\\\"}) (TxIx {unTxIx = 0})]))))))\\"","status_code":400}""";

    private static DevnetKit kit;
    private static PaymentFactory payments;
    private static FacilitatorClient facilitator;
    private static ShimClient shim;
    private static Wallet receiver;

    @BeforeAll
    static void stack() {
        kit = DevnetKit.fromEnv();
        payments = new PaymentFactory(kit);
        facilitator = FacilitatorClient.defaultInstance();
        shim = ShimClient.fromEnv();
        shim.reset();
        receiver = Funding.wallet(5);
    }

    // SettlementService.java:99-100 (Unknown outcome -> pending), :137-142 (resume never rebroadcasts)
    @Test
    void aLostSubmitAnswerIsPendingThenTheLandedTxConfirmsWithoutRebroadcast() {
        Payment payment = freshPayment();
        try {
            shim.registerFault("lost-answer", Map.of("sha256", ShimClient.sha256Hex(payment.txBytes())),
                    Map.of("type", "drop-response"));
            assertPending(settle(payment), payment);
            // The shim forwarded it before answering 502, so it lands.
            kit.assertOnChain(payment.txHash());
            JsonNode confirmed = Poll.until("re-settle confirmed", CONVERGE_DEADLINE, () -> {
                JsonNode body = settle(payment);
                return body.path("success").asBoolean() ? Optional.of(body) : Optional.empty();
            });
            assertThat(confirmed.path("extra").path("status").asText()).isEqualTo("confirmed");
            assertThat(confirmed.path("transaction").asText()).isEqualTo(payment.txHash());
            assertThat(shim.submitCount(payment.txBytes())).isEqualTo(1);
        } finally {
            shim.reset();
        }
    }

    // BlockfrostChainService.java:239-243 (400 + ApplyTxError -> Rejected); SettlementService.java:133-135
    @Test
    void aLedgerRejectionIsDefinitiveAndStaysSoOnResettle() {
        Payment payment = freshPayment();
        try {
            shim.registerFault("ledger-reject", Map.of("sha256", ShimClient.sha256Hex(payment.txBytes())),
                    Map.of("type", "ledger-reject", "body", LEDGER_REJECTION));
            for (int attempt = 0; attempt < 2; attempt++) {
                JsonNode body = settle(payment);
                assertThat(body.path("success").asBoolean()).as("%s", body).isFalse();
                assertThat(body.path("errorReason").asText()).as("attempt %d: %s", attempt, body)
                        .isEqualTo("exact_cardano_settlement_definitively_rejected");
                assertThat(body.path("transaction").asText()).isEqualTo(payment.txHash());
            }
            // Received once (never forwarded): the second answer came from the journal.
            assertThat(shim.submitCount(payment.txBytes())).isEqualTo(1);
            assertThat(kit.isOnChain(payment.txHash())).isFalse();
        } finally {
            shim.reset();
        }
    }

    // A swallowed submit answers 200 with the right hash, so the row goes Accepted -> SUBMITTED ->
    // NOT_CONFIRMED -> pending (SettlementService.java:102-121, 197-203); the re-settle resumes into the same branch. NOTHING is asserted
    // about expiry (class comment). The first settle waits the whole confirmation timeout (75 s).
    @Test
    void aSwallowedSubmitStaysPendingAndIsNeverRebroadcast() {
        Payment payment = freshPayment();
        try {
            shim.registerFault("swallow", Map.of("sha256", ShimClient.sha256Hex(payment.txBytes())),
                    Map.of("type", "swallow", "txHash", payment.txHash()));
            assertPending(settle(payment), payment);
            assertPending(settle(payment), payment);
            assertThat(shim.submitCount(payment.txBytes())).as("received, never forwarded").isEqualTo(1);
            assertThat(kit.isOnChain(payment.txHash())).isFalse();
        } finally {
            shim.reset();
        }
    }

    // ExactCardanoScheme.java:238 (input without a coin value)
    @Test
    void anInputWithoutACoinValueIsInputValueUnavailable() {
        Payment payment = freshPayment();
        TransferAssert.assertValid(payment); // control: healthy without the fault
        try {
            shim.registerFault("no-coin", Map.of("outRef", payment.nonce()), Map.of("type", "drop-lovelace"));
            JsonNode verified = verify(payment);
            assertThat(verified.path("isValid").asBoolean()).as("%s", verified).isFalse();
            assertThat(verified.path("invalidReason").asText()).isEqualTo("exact_cardano_facilitator_input_value_unavailable");
        } finally {
            shim.reset();
        }
        TransferAssert.assertValid(payment);
        assertThat(shim.submitCount(payment.txBytes())).isZero();
    }

    // SettlementService.java:190-193 (NotSeen on a CONFIRMED row -> pending, no rebroadcast)
    @Test
    void aHiddenConfirmedTxIsPendingWithoutRebroadcastAndConfirmsAgainWhenShown() {
        Payment payment = freshPayment();
        JsonNode first = settle(payment);
        assertThat(first.path("success").asBoolean()).as("%s", first).isTrue();
        try {
            shim.registerFault("hide", Map.of("txHash", payment.txHash()), Map.of("type", "hide-tx"));
            assertPending(settle(payment), payment);
            assertThat(shim.submitCount(payment.txBytes())).isEqualTo(1);
        } finally {
            shim.reset();
        }
        JsonNode again = Poll.until("re-settle confirmed after un-hiding", CONVERGE_DEADLINE, () -> {
            JsonNode body = settle(payment);
            return body.path("success").asBoolean() ? Optional.of(body) : Optional.empty();
        });
        assertThat(again.path("extra").path("status").asText()).isEqualTo("confirmed");
        assertThat(shim.submitCount(payment.txBytes())).isEqualTo(1);
    }

    // BlockfrostChainService.java openUtxoLookup (a non-404 lookup failure throws ChainLookupException)
    @Test
    void aRateLimitedUtxoLookupFailsVerifyClosedAsChainLookupFailed() {
        Payment payment = freshPayment();
        try {
            shim.registerFault("lookup-429", Map.of("outRef", payment.nonce()), Map.of("type", "status", "status", 429));
            JsonNode verified = verify(payment);
            assertThat(verified.path("isValid").asBoolean()).as("%s", verified).isFalse();
            assertThat(verified.path("invalidReason").asText()).isEqualTo("exact_cardano_facilitator_chain_lookup_failed");
        } finally {
            shim.reset();
        }
        TransferAssert.assertValid(payment);
    }

    // ---- helpers ----

    private static Payment freshPayment() {
        return payments.from(Funding.wallet(10)).payTo(receiver).lovelace(2_000_000).build();
    }

    private static JsonNode settle(Payment payment) {
        FacilitatorClient.Response response = facilitator.settle(payment.body());
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return SchemaAssert.assertMatches(SchemaAssert.SETTLE, response);
    }

    private static JsonNode verify(Payment payment) {
        FacilitatorClient.Response response = facilitator.verify(payment.body());
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return SchemaAssert.assertMatches(SchemaAssert.VERIFY, response);
    }

    private static void assertPending(JsonNode body, Payment payment) {
        assertThat(body.path("success").asBoolean()).as("%s", body).isFalse();
        assertThat(body.path("errorReason").asText()).as("%s", body).isEqualTo("settlement_pending");
        assertThat(body.path("transaction").asText()).isEqualTo(payment.txHash());
    }
}
