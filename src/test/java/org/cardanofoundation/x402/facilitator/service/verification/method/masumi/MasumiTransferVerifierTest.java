package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.protocol.VerifyResponse;
import org.cardanofoundation.x402.facilitator.service.verification.ExactCardanoScheme;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.service.verification.method.DefaultTransferVerifier;
import org.cardanofoundation.x402.facilitator.testutil.FakeChainService;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-verify coverage for {@link MasumiTransferVerifier} (the {@code masumi}
 * assetTransferMethod), driven through {@link ExactCardanoScheme} exactly like a
 * live request. Ports the demo's Masumi suite and adds the canonical TS rules the
 * demo lacked: reference-script rejection, non-zero cooldowns, the M8 deadline,
 * collateral bounds, and return-address matching.
 */
class MasumiTransferVerifierTest {

    FakeChainService chain;
    ExactCardanoScheme scheme;

    @BeforeEach
    void setUp() {
        chain = new FakeChainService();
        chain.unspent.put(TestTx.NONCE, TestTx.PAYER_ADDRESS); // nonce unspent, owned by payer
        chain.currentSlot = 500_000L;
        scheme = new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier(), new MasumiTransferVerifier()), 32768);
    }

    static Map<String, Object> defaultExtra() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assetTransferMethod", "masumi");
        m.put("contractAddress", TestTx.PAY_TO);
        m.put("sellerAddress", TestTx.SELLER_ADDRESS);
        m.put("referenceKey", TestTx.MASUMI_REFERENCE_KEY);
        m.put("referenceSignature", TestTx.MASUMI_REFERENCE_SIGNATURE);
        m.put("sellerNonce", TestTx.MASUMI_SELLER_NONCE);
        m.put("identifierFromPurchaser", TestTx.MASUMI_IDENTIFIER_FROM_PURCHASER);
        m.put("agentIdentifier", TestTx.MASUMI_AGENT_IDENTIFIER);
        m.put("inputHash", TestTx.MASUMI_INPUT_HASH);
        m.put("collateralReturnLovelace", TestTx.MASUMI_COLLATERAL_RETURN_LOVELACE.toString());
        m.put("payByTime", TestTx.MASUMI_PAY_BY_TIME.toString());
        m.put("submitResultTime", TestTx.MASUMI_SUBMIT_RESULT_TIME.toString());
        m.put("unlockTime", TestTx.MASUMI_UNLOCK_TIME.toString());
        m.put("externalDisputeUnlockTime", TestTx.MASUMI_EXTERNAL_DISPUTE_UNLOCK_TIME.toString());
        return m;
    }

    PaymentRequirements requirements(Map<String, Object> extra) {
        return new PaymentRequirements("exact", "cardano:preprod", "lovelace",
                TestTx.MASUMI_AMOUNT.toString(), TestTx.PAY_TO, 600, extra);
    }

    PaymentPayload payload(String txB64, PaymentRequirements accepted) {
        Map<String, Object> p = new HashMap<>();
        p.put("transaction", txB64);
        p.put("nonce", TestTx.NONCE);
        return new PaymentPayload(2, null, accepted, p, null);
    }

    VerifyResponse verify(String txB64, Map<String, Object> extra) {
        PaymentRequirements req = requirements(extra);
        return scheme.verify(payload(txB64, req), req);
    }

    VerifyResponse verify(TestTx.MasumiSpec spec, Map<String, Object> extra) {
        return verify(TestTx.buildMasumiLockBase64(TestTx.PAY_TO, spec), extra);
    }

    @Test void happyPath() {
        VerifyResponse r = verify(TestTx.MasumiSpec.defaults(), defaultExtra());
        assertThat(r.invalidReason()).isNull();
        assertThat(r.isValid()).isTrue();
        assertThat(r.payer()).isEqualTo(TestTx.PAYER_ADDRESS);
    }

    @Test void decoderCapturesOnChainInlineDatumLength() {
        // M9 uses the on-chain datum byte length read from the raw wire bytes (TS
        // datumHex.length/2), not cardano-client-lib's re-serialization. Confirm the
        // decoder extracts it and it equals the datum the tx was actually built with.
        TestTx.MasumiSpec spec = TestTx.MasumiSpec.defaults();
        var decoded = new CardanoTransactionDecoder().decode(
                TestTx.buildMasumiLockBase64(TestTx.PAY_TO, spec));
        int expected = TestTx.buildMasumiDatum(spec).serializeToBytes().length;
        assertThat(decoded.outputs().get(0).inlineDatumRawLen()).isEqualTo(expected).isPositive();
        assertThat(decoded.outputs().get(1).inlineDatumRawLen()).isZero(); // change output
    }

    @Test void rejectsContractMismatch() {
        Map<String, Object> extra = defaultExtra();
        extra.put("contractAddress", TestTx.SELLER_ADDRESS);
        assertThat(verify(TestTx.MasumiSpec.defaults(), extra).invalidReason())
                .isEqualTo(ErrorCodes.MASUMI_CONTRACT_MISMATCH);
    }

    @Test void rejectsMissingDatum() {
        String tx = TestTx.buildBase64(TestTx.Spec.defaults().withAmount(TestTx.MASUMI_AMOUNT));
        assertThat(verify(tx, defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_MISSING);
    }

    @Test void rejectsReferenceScript() {
        String tx = TestTx.buildMasumiLockBase64(TestTx.PAY_TO, TestTx.MasumiSpec.defaults(),
                1_000_000L, true, null);
        assertThat(verify(tx, defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_REFERENCE_SCRIPT);
    }

    @Test void rejectsWrongConstrAlt() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withRootAlt(1), defaultExtra()).invalidReason())
                .isEqualTo(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsWrongFieldCount() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withFieldCount(18), defaultExtra()).invalidReason())
                .isEqualTo(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsStateNotFundsLocked() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withStateAlt(1), defaultExtra()).invalidReason())
                .isEqualTo(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsNonEmptyResultHash() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withResultHashHex("aa"), defaultExtra()).invalidReason())
                .isEqualTo(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsNonIntegerCooldown() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withCooldownCorrupt(true), defaultExtra()).invalidReason())
                .isEqualTo(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsNonZeroCooldown() {
        String tx = TestTx.buildMasumiLockBase64(TestTx.PAY_TO, TestTx.MasumiSpec.defaults(),
                1_000_000L, false, BigInteger.valueOf(1));
        assertThat(verify(tx, defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsShortReferenceSignature() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withReferenceSignatureHex("aabbccddeeff0011"),
                defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsBadTimeOrdering() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withPayByTime(new BigInteger("2000000700000")),
                defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsScriptCredBuyer() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withBuyerIsScript(true), defaultExtra()).invalidReason())
                .isEqualTo(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsScriptCredSeller() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withSellerIsScript(true), defaultExtra()).invalidReason())
                .isEqualTo(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsDeadlineNoTtl() {
        String tx = TestTx.buildMasumiLockBase64(TestTx.PAY_TO, TestTx.MasumiSpec.defaults(),
                null, false, null);
        assertThat(verify(tx, defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DEADLINE);
    }

    @Test void rejectsDeadlineTtlAfterPayBy() {
        // A far-future TTL slot maps to a POSIX time past pay_by_time.
        String tx = TestTx.buildMasumiLockBase64(TestTx.PAY_TO, TestTx.MasumiSpec.defaults(),
                900_000_000L, false, null);
        assertThat(verify(tx, defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DEADLINE);
    }

    @Test void rejectsCollateralBelowFloor() {
        // 0 < collateral < MASUMI_MIN_COLLATERAL_LOVELACE.
        assertThat(verify(TestTx.MasumiSpec.defaults().withCollateralReturnLovelace(BigInteger.ONE),
                defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_COLLATERAL);
    }

    @Test void rejectsCollateralAboveLockedCoin() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withCollateralReturnLovelace(BigInteger.valueOf(6_000_000L)),
                defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_COLLATERAL);
    }

    @Test void rejectsBuyerNotPayer() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withBuyerAddress(TestTx.SELLER_ADDRESS),
                defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_MISMATCH);
    }

    @Test void rejectsSellerMismatch() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withSellerAddress(TestTx.PAYER_ADDRESS),
                defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_MISMATCH);
    }

    @Test void rejectsReturnAddressMismatch() {
        // Datum carries None for buyer_return_address, but extra declares one.
        Map<String, Object> extra = defaultExtra();
        extra.put("buyerReturnAddress", TestTx.SELLER_ADDRESS);
        assertThat(verify(TestTx.MasumiSpec.defaults(), extra).invalidReason())
                .isEqualTo(ErrorCodes.MASUMI_DATUM_MISMATCH);
    }

    @Test void rejectsHexFieldMismatch() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withReferenceKeyHex("ffffffff"),
                defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_MISMATCH);
    }

    @Test void rejectsTimeFieldMismatch() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withUnlockTime(new BigInteger("2000001300000")),
                defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_MISMATCH);
    }
}
