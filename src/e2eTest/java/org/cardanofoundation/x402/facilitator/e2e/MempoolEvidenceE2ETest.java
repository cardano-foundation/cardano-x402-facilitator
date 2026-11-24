package org.cardanofoundation.x402.facilitator.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Funding;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.SchemaAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.ShimClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A provider that reports mempool status, which Yano never does on its own: the shim answers
 * {@code /mempool/<hash>} with 200 and hides {@code /txs/<hash>} until a few seconds after submit.
 *
 * <p>On the default instance (mempool acceptance off), {@code l1Confirmations: -1} must not stop at
 * mempool sight: SettlementService awaits inclusion at depth 0 and answers {@code confirmed} once
 * the transaction is visible again. Before WP-15 it awaited depth -1, saw the mempool and answered
 * {@code settlement_pending} at once.
 *
 * <p>{@code @Isolated}: it holds the stack's default instance in a long settle while the shim hides
 * a transaction; IsolatedStackGuard clears the faults afterwards as well.
 */
@Isolated
@StackRequired
class MempoolEvidenceE2ETest {

    private static final Duration HIDDEN_FOR = Duration.ofSeconds(5);

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

    // SettlementService.java:119 (await depth max(0, -1) without opt-in), :175 (Included -> confirmed)
    @Test
    void mempoolEvidenceAloneDoesNotAnswerAMinusOnePolicyWithoutMempoolAcceptance() throws Exception {
        Payment payment = payments.from(Funding.wallet(10)).payTo(receiver).lovelace(2_000_000).build()
                .withExtra("confirmationPolicy", Map.of("l1Confirmations", -1));
        ScheduledExecutorService unhide = Executors.newSingleThreadScheduledExecutor();
        try {
            shim.registerFault("in-mempool", Map.of("txHash", payment.txHash()), Map.of("type", "mempool"));
            shim.registerFault("hidden", Map.of("txHash", payment.txHash()), Map.of("type", "hide-tx"));
            long hiddenAt = System.nanoTime();
            ScheduledFuture<?> unhidden = unhide.schedule(() -> shim.deleteFault("hidden"),
                    HIDDEN_FOR.toMillis(), TimeUnit.MILLISECONDS);

            FacilitatorClient.Response response = facilitator.settle(payment.body());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - hiddenAt);
            // A failed un-hide would otherwise surface only as a misleading settlement_pending.
            if (unhidden.isDone()) unhidden.get();

            assertThat(response.status()).as("%s", response).isEqualTo(200);
            JsonNode settled = SchemaAssert.assertMatches(SchemaAssert.SETTLE, response);
            assertThat(settled.path("success").asBoolean()).as("%s", settled).isTrue();
            assertThat(settled.path("extra").path("status").asText()).as("%s", settled).isEqualTo("confirmed");
            assertThat(settled.path("extra").path("confirmations").asInt()).isGreaterThanOrEqualTo(0);
            assertThat(settled.path("transaction").asText()).isEqualTo(payment.txHash());
            assertThat(elapsed).as("answered only after the transaction became visible").isGreaterThanOrEqualTo(HIDDEN_FOR);
            kit.assertOnChain(payment.txHash());
            assertThat(shim.submitCount(payment.txBytes())).isEqualTo(1);
        } finally {
            unhide.shutdownNow();
            shim.reset();
        }
    }
}
