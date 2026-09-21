package org.cardanofoundation.x402.facilitator.service.settlement;

import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Replacement for removed client-submission negotiation: submission and durable evidence retries. */
class ClientSubmissionTest {
    static SettlementRepository repo;
    static NamedParameterJdbcTemplate jdbc;
    FakeChainService chain;
    ExactCardanoScheme scheme;

    @BeforeAll static void initDb() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:clientsubmit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").schemas("facilitator")
                .defaultSchema("facilitator").createSchemas(true).load().migrate();
        jdbc = new NamedParameterJdbcTemplate(ds);
        repo = new SettlementRepository(jdbc);
    }

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM facilitator.settlement", Map.of());
        chain = new FakeChainService();
        chain.unspent.put(TestTx.NONCE, TestTx.PAYER_ADDRESS);
        scheme = new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier()), 32768, ShelleyNetworkClock.forNetwork("cardano:preprod", null));
    }

    SettlementService service(boolean mempool) {
        return new SettlementService(repo, scheme, chain, new CardanoTransactionDecoder(),
                new SettlementService.Config(Duration.ofMillis(20), 1, mempool, false,
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

    @Test void obsoleteClientModeCannotSuppressFacilitatorBroadcast() {
        var req = requirements(1);
        var p = payload(req);
        var oldMode = new PaymentPayload(2, p.resource(), req, Map.of("transaction", p.payload().get("transaction"),
                "nonce", TestTx.NONCE, "submissionMode", "client"), null);
        assertThat(service(false).settle(oldMode, req).success()).isTrue();
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test void freshSpentNonceStillFailsWithoutSubmissionProvenance() {
        chain.unspent.clear();
        chain.spentOwners.put(TestTx.NONCE, TestTx.PAYER_ADDRESS);
        assertThat(service(false).settle(payload(requirements(1)), requirements(1)).errorReason())
                .isEqualTo(ErrorCodes.NONCE_NOT_ON_CHAIN);
        assertThat(chain.submitCount).isZero();
    }

    @Test void alreadyBroadcastRetryUsesStoredPayerWhenSpentInputOwnerUnavailable() {
        var req = requirements(1);
        assertThat(service(false).settle(payload(req), req).success()).isTrue();
        chain.unspent.clear();
        assertThat(service(false).settle(payload(req), req).success()).isTrue();
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test void mempoolDoesNotSatisfyZeroConfirmations() {
        chain.includedDepth = FakeChainService.MEMPOOL;
        var req = requirements(0);
        var pending = service(true).settle(payload(req), req);
        assertThat(pending.errorReason()).isEqualTo("settlement_pending");
        assertThat(pending.extra()).containsEntry("confirmations", -1);
        chain.includedDepth = 0;
        var included = service(true).settle(payload(req), req);
        assertThat(included.success()).isTrue();
        assertThat(included.extra()).containsEntry("confirmations", 0);
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test void minusOneWithoutOperatorOptInStillRequiresCanonicalInclusion() {
        chain.includedDepth = FakeChainService.MEMPOOL;
        var req = requirements(-1);
        assertThat(service(false).settle(payload(req), req).success()).isFalse();
        chain.includedDepth = 0;
        assertThat(service(false).settle(payload(req), req).success()).isTrue();
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test void unknownSubmissionNeverBecomesInventedMempoolAcceptance() {
        chain.includedDepth = FakeChainService.NOT_SEEN;
        chain.submissionResult = new SubmissionResult.Unknown("timeout after send");
        var req = requirements(-1);
        assertThat(service(true).settle(payload(req), req).errorReason()).isEqualTo("settlement_pending");
        assertThat(service(true).settle(payload(req), req).success()).isFalse();
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test void independentMempoolEvidenceCanResolveUnknownLocalSubmission() {
        chain.includedDepth = FakeChainService.NOT_SEEN;
        chain.submissionResult = new SubmissionResult.Unknown("timeout after send");
        var req = requirements(-1);
        assertThat(service(true).settle(payload(req), req).success()).isFalse();
        chain.includedDepth = FakeChainService.MEMPOOL;
        assertThat(service(true).settle(payload(req), req).success()).isTrue();
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test void providerOutageAfterBroadcastReturnsPendingWithCanonicalHash() {
        chain.includedDepth = FakeChainService.NOT_SEEN;
        var req = requirements(1);
        SettleResponse first = service(false).settle(payload(req), req);
        chain.throwOnInclusionCheck = true;
        chain.throwOnLookup = true;
        var retry = service(false).settle(payload(req), req);
        assertThat(retry.errorReason()).isEqualTo("settlement_pending");
        assertThat(retry.transaction()).isEqualTo(first.transaction());
        assertThat(retry.extra()).containsEntry("status", "pending");
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test void healthGateLetsDurableRetriesReachJournalDuringProviderOutage() {
        var req = requirements(1);
        service(false).settle(payload(req), req);
        chain.throwOnLookup = true;
        var gate = new SettlementGate(Map.of("cardano:preprod", chain), repo, new CardanoTransactionDecoder());
        assertThat(gate.isHealthy("cardano:preprod")).isFalse();
        assertThat(gate.isHealthy("cardano:preprod", payload(req))).isTrue();
    }
}
