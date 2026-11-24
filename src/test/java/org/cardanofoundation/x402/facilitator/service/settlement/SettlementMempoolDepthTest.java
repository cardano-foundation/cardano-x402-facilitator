package org.cardanofoundation.x402.facilitator.service.settlement;

import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord.Status;
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

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Without operator opt-in to mempool acceptance, {@code l1Confirmations: -1} on a fresh,
 * accepted submission waits for canonical inclusion instead of stopping at mempool sight.
 */
class SettlementMempoolDepthTest {
    static SettlementRepository repo;
    static NamedParameterJdbcTemplate jdbc;
    RecordingChain chain;
    ExactCardanoScheme scheme;

    /** Records every minDepth the settle path waits for; mempool is reached first, inclusion later. */
    static final class RecordingChain extends FakeChainService {
        final List<Integer> awaitedDepths = new ArrayList<>();
        boolean neverIncluded;
        /** Models a concurrent retry raising the stored depth between submit and the wait. */
        boolean raiseStoredDepthOnSubmit;

        @Override
        public SubmissionResult submitTransaction(byte[] txBytes) {
            SubmissionResult result = super.submitTransaction(txBytes);
            if (raiseStoredDepthOnSubmit) {
                jdbc.update("UPDATE facilitator.settlement SET selected_confirmations = 0 WHERE tx_hash = :hash",
                        Map.of("hash", submittedTxHash));
            }
            return result;
        }

        @Override
        public InclusionResult awaitInclusion(String txHashHex, int minDepth, Duration timeout) {
            awaitedDepths.add(minDepth);
            if (neverIncluded || minDepth <= MEMPOOL) return new InclusionResult.Mempool();
            return new InclusionResult.Included(0, currentSlot, "blockhash");
        }
    }

    @BeforeAll static void initDb() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:mempooldepth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").schemas("facilitator")
                .defaultSchema("facilitator").createSchemas(true).load().migrate();
        jdbc = new NamedParameterJdbcTemplate(ds);
        repo = new SettlementRepository(jdbc);
    }

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM facilitator.settlement", Map.of());
        chain = new RecordingChain();
        // The rest of the fake agrees: a fallback to checkInclusion sees mempool, never Included(1).
        chain.includedDepth = FakeChainService.MEMPOOL;
        chain.unspent.put(TestTx.NONCE, TestTx.PAYER_ADDRESS);
        scheme = new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier()), 32768, ShelleyNetworkClock.forNetwork("cardano:preprod", null));
    }

    SettlementService service(boolean acceptMempool) {
        return new SettlementService(repo, scheme, chain, new CardanoTransactionDecoder(),
                new SettlementService.Config(Duration.ofMillis(20), 1, acceptMempool, false,
                        Duration.ofMinutes(10), Duration.ofSeconds(2)), Clock.systemUTC());
    }

    PaymentRequirements requirements(int depth) {
        return new PaymentRequirements("exact", "cardano:preprod", "lovelace", "2000000", TestTx.PAY_TO,
                600, Map.of("assetTransferMethod", "default", "confirmationPolicy", Map.of("l1Confirmations", depth)));
    }

    PaymentPayload payload(PaymentRequirements requirements) {
        return new PaymentPayload(2, Map.of("url", "https://example.test/a"), requirements,
                Map.of("transaction", TestTx.buildBase64(TestTx.Spec.defaults()), "nonce", TestTx.NONCE), null);
    }

    SettlementRecord journal() {
        return repo.find(chain.submittedTxHash).orElseThrow();
    }

    @Test void minusOneWithoutMempoolAcceptanceWaitsForInclusionAndConfirms() {
        PaymentRequirements req = requirements(-1);
        SettleResponse settled = service(false).settle(payload(req), req);

        assertThat(settled.success()).as("settle succeeds once included").isTrue();
        assertThat(settled.extra()).containsEntry("status", "confirmed").containsEntry("confirmations", 0);
        assertThat(chain.awaitedDepths).as("waits past mempool for depth 0").containsExactly(0);
        assertThat(chain.submitCount).isEqualTo(1);
        assertThat(journal().status()).isEqualTo(Status.CONFIRMED);
        assertThat(journal().selectedConfirmations()).as("requested depth stays as requested").isEqualTo(-1);
    }

    @Test void minusOneWithoutMempoolAcceptanceStaysPendingWhileOnlyInMempool() {
        chain.neverIncluded = true;
        PaymentRequirements req = requirements(-1);
        SettleResponse pending = service(false).settle(payload(req), req);

        assertThat(pending.success()).isFalse();
        assertThat(pending.errorReason()).isEqualTo("settlement_pending");
        assertThat(pending.extra()).containsEntry("confirmations", -1);
        assertThat(journal().status()).isEqualTo(Status.NOT_CONFIRMED);
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test void positiveDepthIsAwaitedAsRequested() {
        PaymentRequirements req = requirements(1);
        service(false).settle(payload(req), req);

        assertThat(chain.awaitedDepths).as("a requested depth of 1 is not lowered").containsExactly(1);
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test void minusOneWithMempoolAcceptanceAnswersAtSubmissionWithoutWaiting() {
        PaymentRequirements req = requirements(-1);
        SettleResponse settled = service(true).settle(payload(req), req);

        assertThat(settled.success()).isTrue();
        assertThat(settled.extra()).containsEntry("status", "mempool").containsEntry("confirmations", -1);
        assertThat(chain.awaitedDepths).as("accept-mempool answers from the accepted submission").isEmpty();
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test void withMempoolAcceptanceTheWaitStillUsesTheRequestedDepth() {
        // The stored depth no longer reads -1, so the early mempool answer is skipped and the
        // wait runs: with operator opt-in it must keep the requested -1, not the fix's depth 0.
        chain.raiseStoredDepthOnSubmit = true;
        PaymentRequirements req = requirements(-1);
        service(true).settle(payload(req), req);

        assertThat(chain.awaitedDepths).as("accept-mempool keeps the requested depth").containsExactly(-1);
        assertThat(chain.submitCount).isEqualTo(1);
    }
}
