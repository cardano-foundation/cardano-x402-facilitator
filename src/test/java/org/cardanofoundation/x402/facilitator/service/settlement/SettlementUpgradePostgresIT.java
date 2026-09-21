package org.cardanofoundation.x402.facilitator.service.settlement;

import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord.Status;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.repository.SettlementRepository;
import org.cardanofoundation.x402.facilitator.service.verification.ExactCardanoScheme;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.service.verification.method.DefaultTransferVerifier;
import org.cardanofoundation.x402.facilitator.testutil.FakeChainService;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual V1->V2 upgrades, including ambiguous client-mode and unknown-provider provenance. */
@Testcontainers
class SettlementUpgradePostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    SettlementRepository repo;
    FakeChainService chain;
    PaymentRequirements req;
    PaymentPayload payload;
    String hash;

    @BeforeEach void setupV1() throws Exception {
        ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).schemas("facilitator").defaultSchema("facilitator")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(ds).schemas("facilitator").defaultSchema("facilitator")
                .createSchemas(true).target("1").load().migrate();
        jdbc = new JdbcTemplate(ds);
        repo = new SettlementRepository(new NamedParameterJdbcTemplate(ds));
        chain = new FakeChainService();
        chain.includedDepth = FakeChainService.NOT_SEEN;
        chain.spentOwners.put(TestTx.NONCE, TestTx.PAYER_ADDRESS);
        req = new PaymentRequirements("exact", "cardano:preprod", "lovelace", "2000000", TestTx.PAY_TO,
                600, Map.of("assetTransferMethod", "default", "confirmationPolicy", Map.of("l1Confirmations", 3)));
        payload = new PaymentPayload(2, Map.of("url", "https://example.test/a"), req,
                Map.of("transaction", TestTx.buildBase64(TestTx.Spec.defaults()), "nonce", TestTx.NONCE), null);
        hash = new CardanoTransactionDecoder().decode((String) payload.payload().get("transaction")).txHashHex();
    }

    void seedV1(Status status, String txHash) {
        jdbc.update("""
                INSERT INTO facilitator.settlement
                    (tx_hash, attempt_id, requirements_digest, network, status, payer, pay_to, asset, amount,
                     transfer_method, nonce_outref, tx_ttl_slot, claimed_at, submitted_at, confirmed_at, response_json)
                VALUES (?, ?, ?, 'cardano:preprod', ?, ?, ?, 'lovelace', 2000000, 'default', ?, 1000000,
                        ?, ?, ?, ?)
                """, txHash, UUID.randomUUID(), SettlementDigest.computeLegacy(req, payload.resource()),
                status.name(), TestTx.PAYER_ADDRESS, TestTx.PAY_TO, TestTx.NONCE,
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(5))),
                status == Status.CLAIMED ? null : java.sql.Timestamp.from(Instant.now().minusSeconds(500)),
                status == Status.CONFIRMED ? java.sql.Timestamp.from(Instant.now()) : null,
                status == Status.CONFIRMED ? "{\"success\":true}" : null);
    }

    void upgrade() {
        Flyway.configure().dataSource(ds).schemas("facilitator").defaultSchema("facilitator").load().migrate();
    }

    SettlementService service() {
        var verifier = new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier()), 32768, ShelleyNetworkClock.forNetwork("cardano:preprod", null));
        return new SettlementService(repo, verifier, chain, new CardanoTransactionDecoder(),
                new SettlementService.Config(Duration.ofMillis(20), 1, true, true,
                        Duration.ofMinutes(10), Duration.ofSeconds(2)), Clock.systemUTC());
    }

    void sweep() {
        new SettlementReconciler(repo, Map.of("cardano:preprod", chain), ds, 1,
                Duration.ofMinutes(10), Duration.ofHours(24), Clock.systemUTC(), true).sweep();
    }

    @Test void migrationPreservesEveryV1StateWithoutInventingPolicyTermsOrAcceptance() {
        int i = 0;
        for (Status status : Status.values()) seedV1(status, "%064x".formatted(++i));
        upgrade();
        i = 0;
        for (Status status : Status.values()) {
            SettlementRecord row = repo.find("%064x".formatted(++i)).orElseThrow();
            assertThat(row.status()).isEqualTo(status);
            assertThat(row.selectedConfirmations()).isNull();
            assertThat(row.termsDigest()).isNull();
            assertThat(row.submissionAccepted()).isFalse();
            assertThat(row.isLegacy()).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM facilitator.settlement", Integer.class)).isEqualTo(7);
    }

    @ParameterizedTest @EnumSource(Status.class)
    void everyLegacyStatusIsObservationOnlyUntilCanonicalEvidenceAndVerifiedRetry(Status status) {
        seedV1(status, hash);
        upgrade();
        var pending = service().settle(payload, req);
        assertThat(pending.success()).isFalse();
        assertThat(pending.errorReason()).isEqualTo("settlement_pending");
        assertThat(chain.submitCount).isZero();
        assertThat(repo.find(hash).orElseThrow().selectedConfirmations()).isNull();

        // Operator depth is 1; independent evidence below the requested 3 remains pending.
        chain.includedDepth = 1;
        assertThat(service().settle(payload, req).success()).isFalse();
        assertThat(repo.find(hash).orElseThrow().selectedConfirmations()).isEqualTo(3);
        chain.includedDepth = 4;
        var success = service().settle(payload, req);
        assertThat(success.success()).isTrue();
        assertThat(success.extra()).containsEntry("confirmations", 4);
        assertThat(chain.submitCount).isZero();
    }

    @ParameterizedTest @EnumSource(Status.class)
    void reconcilerNeverPromotesLegacyPolicyUnknownRowsUnderOperatorDefault(Status status) {
        seedV1(status, hash);
        upgrade();
        chain.includedDepth = 10;
        sweep();
        SettlementRecord row = repo.find(hash).orElseThrow();
        assertThat(row.selectedConfirmations()).isNull();
        assertThat(row.status()).isEqualTo(status);
        assertThat(row.submissionAccepted()).isFalse();
        assertThat(chain.submitCount).isZero();
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"SUBMITTING", "SUBMITTED", "NOT_CONFIRMED", "FAILED", "CLAIMED"})
    void legacyExpiryNeverReopensBroadcast(Status status) {
        seedV1(status, hash);
        upgrade();
        chain.currentSlot = 2_000_000;
        sweep();
        assertThat(repo.find(hash).orElseThrow().status()).isEqualTo(Status.EXPIRED);
        assertThat(service().settle(payload, req).errorReason()).isEqualTo("exact_cardano_settlement_failed");
        assertThat(chain.submitCount).isZero();
        assertThat(repo.find(hash).orElseThrow().isLegacy()).isTrue();
    }

    @Test void validatedRetryReplacesUnauthenticatedHistoricalPayerBeforeBackgroundPromotion() {
        seedV1(Status.SUBMITTED, hash);
        jdbc.update("UPDATE facilitator.settlement SET payer = ? WHERE tx_hash = ?", TestTx.PAY_TO, hash);
        upgrade();
        chain.includedDepth = 1;
        assertThat(service().settle(payload, req).success()).isFalse();
        assertThat(repo.find(hash).orElseThrow().payer()).isEqualTo(TestTx.PAYER_ADDRESS);
        chain.includedDepth = 3;
        sweep();
        assertThat(repo.find(hash).orElseThrow().status()).isEqualTo(Status.CONFIRMED);
        assertThat(repo.find(hash).orElseThrow().responseJson()).contains(TestTx.PAYER_ADDRESS);
    }

    @Test void oldConfirmedRowCannotReturnCachedSuccessOnRollback() {
        seedV1(Status.CONFIRMED, hash);
        upgrade();
        assertThat(service().settle(payload, req).success()).isFalse();
        assertThat(chain.submitCount).isZero();
    }

    @Test void legacyStatusAloneCannotEnableSpentInputVerificationProfile() {
        seedV1(Status.SUBMITTED, hash);
        upgrade();
        chain.spentOwners.clear();
        chain.includedDepth = FakeChainService.MEMPOOL;
        assertThat(service().settle(payload, req).success()).isFalse();
        assertThat(repo.find(hash).orElseThrow().selectedConfirmations()).isNull();
        assertThat(chain.submitCount).isZero();
    }
}
