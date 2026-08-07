package org.cardanofoundation.x402.facilitator.service.settlement;

import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.protocol.SettleResponse;
import org.cardanofoundation.x402.facilitator.model.protocol.VerifyResponse;
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
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client submission: the payer broadcasts, and the facilitator authenticates
 * the transaction rather than sending it.
 *
 * <p>The mode inverts two of the server-mode preconditions, so both are covered
 * here rather than assumed: the payment's own inputs are already spent (a
 * server-mode verify would reject exactly the payments that settled), and
 * settlement must never re-broadcast.
 */
class ClientSubmissionTest {

    static DataSource ds;
    static SettlementRepository repo;
    static NamedParameterJdbcTemplate jdbc;

    FakeChainService chain;
    ExactCardanoScheme scheme;

    @BeforeAll
    static void initDb() {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:clientsubmit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
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
        // The payment already consumed its nonce — the defining shape of a
        // client-submitted payment by the time the facilitator sees it.
        chain.spentOwners.put(TestTx.NONCE, TestTx.PAYER_ADDRESS);
        chain.currentSlot = 999_700L;
        scheme = new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier()), 32768,
                ShelleyNetworkClock.forNetwork("cardano:preprod", null));
    }

    SettlementService service(boolean acceptMempool) {
        return new SettlementService(repo, scheme, chain, new CardanoTransactionDecoder(),
                new SettlementService.Config(Duration.ofSeconds(2), 1, acceptMempool, false,
                        Duration.ofMinutes(10), Duration.ofSeconds(2)),
                Clock.systemUTC());
    }

    PaymentRequirements requirements(String policy, Integer l1Confirmations) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "default");
        extra.put("submissionPolicy", policy);
        if (l1Confirmations != null) {
            extra.put("confirmationPolicy", Map.of("l1Confirmations", l1Confirmations));
        }
        return new PaymentRequirements("exact", "cardano:preprod", "lovelace", "2000000",
                TestTx.PAY_TO, 600, extra);
    }

    PaymentPayload payload(String txB64, String mode, PaymentRequirements requirements) {
        Map<String, Object> p = new HashMap<>();
        p.put("transaction", txB64);
        p.put("nonce", TestTx.NONCE);
        if (mode != null) p.put("submissionMode", mode);
        return new PaymentPayload(2, Map.of("url", "https://example.test/a"), requirements, p, null);
    }

    @Test
    void clientSubmittedPaymentVerifiesFromInclusionEvidence() {
        PaymentRequirements req = requirements("client", 1);
        chain.includedDepth = 1;
        VerifyResponse r = scheme.verify(payload(TestTx.buildBase64(TestTx.Spec.defaults()), "client", req), req);
        assertThat(r.isValid()).isTrue();
        // The payer is resolved from the spent nonce's owner, not a live UTXO.
        assertThat(r.payer()).isEqualTo(TestTx.PAYER_ADDRESS);
    }

    @Test
    void clientSubmittedPaymentIsRejectedWhenTheChainHasNoRecord() {
        PaymentRequirements req = requirements("client", 1);
        chain.includedDepth = FakeChainService.NOT_SEEN;
        VerifyResponse r = scheme.verify(payload(TestTx.buildBase64(TestTx.Spec.defaults()), "client", req), req);
        assertThat(r.isValid()).isFalse();
        assertThat(r.invalidReason()).isEqualTo(ErrorCodes.EVIDENCE_MISSING);
    }

    @Test
    void mempoolEvidenceIsEnoughToVerifyButNotToSettleAboveMinusOne() {
        PaymentRequirements req = requirements("client", 0);
        chain.includedDepth = FakeChainService.MEMPOOL;
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        // Verification only asks whether the network has the transaction.
        assertThat(scheme.verify(payload(tx, "client", req), req).isValid()).isTrue();
        // Settlement asks for the depth the 402 demanded, which mempool is not.
        SettleResponse s = service(true).settle(payload(tx, "client", req), req);
        assertThat(s.success()).isFalse();
        assertThat(s.errorReason()).isEqualTo(ErrorCodes.SETTLEMENT_NOT_CONFIRMED);
    }

    @Test
    void settlingAClientSubmittedPaymentNeverRebroadcastsIt() {
        PaymentRequirements req = requirements("client", 1);
        chain.includedDepth = 1;
        SettleResponse r = service(false)
                .settle(payload(TestTx.buildBase64(TestTx.Spec.defaults()), "client", req), req);
        assertThat(r.success()).isTrue();
        assertThat(r.extra()).containsEntry("status", "confirmed");
        // The payer already put it on the network; sending it again is not a
        // harmless retry.
        assertThat(chain.submitCount).isZero();
    }

    @Test
    void serverModeStillSubmitsAndStillRequiresAnUnspentNonce() {
        PaymentRequirements req = requirements("server", 1);
        chain.spentOwners.clear();
        chain.unspent.put(TestTx.NONCE, TestTx.PAYER_ADDRESS);
        SettleResponse r = service(false)
                .settle(payload(TestTx.buildBase64(TestTx.Spec.defaults()), "server", req), req);
        assertThat(r.success()).isTrue();
        assertThat(chain.submitCount).isEqualTo(1);
    }

    @Test
    void serverModeRejectsAPaymentWhoseNonceIsAlreadySpent() {
        // Without evidence there is nothing to distinguish "already settled" from
        // "double spend attempt", so the unspent-input rule still governs.
        PaymentRequirements req = requirements("server", 1);
        VerifyResponse r = scheme.verify(payload(TestTx.buildBase64(TestTx.Spec.defaults()), "server", req), req);
        assertThat(r.isValid()).isFalse();
        assertThat(r.invalidReason()).isEqualTo(ErrorCodes.NONCE_NOT_ON_CHAIN);
    }

    @Test
    void eitherPolicyAdmitsBothModes() {
        PaymentRequirements req = requirements("either", 1);
        chain.includedDepth = 1;
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());
        assertThat(scheme.verify(payload(tx, "client", req), req).isValid()).isTrue();

        chain.spentOwners.clear();
        chain.unspent.put(TestTx.NONCE, TestTx.PAYER_ADDRESS);
        assertThat(scheme.verify(payload(tx, "server", req), req).isValid()).isTrue();
    }

    @Test
    void aModeThePolicyDoesNotAdmitIsRejected() {
        PaymentRequirements req = requirements("server", 1);
        chain.includedDepth = 1;
        VerifyResponse r = scheme.verify(payload(TestTx.buildBase64(TestTx.Spec.defaults()), "client", req), req);
        assertThat(r.isValid()).isFalse();
        assertThat(r.invalidReason()).isEqualTo(ErrorCodes.SUBMISSION_MODE_MISMATCH);
    }

    @Test
    void mempoolSettlementRequiresBothTheOperatorOptInAndAMinusOnePolicy() {
        PaymentRequirements req = requirements("client", -1);
        chain.includedDepth = FakeChainService.MEMPOOL;
        String tx = TestTx.buildBase64(TestTx.Spec.defaults());

        // Operator has not opted in: a -1 policy cannot force reversible evidence.
        SettleResponse refused = service(false).settle(payload(tx, "client", req), req);
        assertThat(refused.success()).isFalse();
        assertThat(refused.errorReason()).isEqualTo(ErrorCodes.SETTLEMENT_NOT_CONFIRMED);

        jdbc.update("DELETE FROM facilitator.settlement", Map.of());
        SettleResponse allowed = service(true).settle(payload(tx, "client", req), req);
        assertThat(allowed.success()).isTrue();
        assertThat(allowed.extra()).containsEntry("status", "mempool");
    }
}
