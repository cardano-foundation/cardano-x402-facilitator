package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.testutil.MasumiTestSeller;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.protocol.VerifyResponse;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;
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
 * live request: reference-script rejection, non-zero cooldowns, the M8 deadline,
 * collateral bounds, and return-address matching.
 */
class MasumiTransferVerifierTest {

    /**
     * The escrow is now derived from the canonical deployment rather than taken
     * from `extra`, so fixtures must lock to the address the verifier computes.
     */
    private static final String ESCROW = MasumiBlueprint.escrowAddress(
            "cardano:preprod", MasumiBlueprint.DEFAULT_DEPLOYMENT);

    FakeChainService chain;
    ExactCardanoScheme scheme;

    @BeforeEach
    void setUp() {
        chain = new FakeChainService();
        chain.unspent.put(TestTx.NONCE, TestTx.PAYER_ADDRESS); // nonce unspent, owned by payer
        chain.currentSlot = 999_700L;
        scheme = new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier(), new MasumiTransferVerifier()), 32768,
                ShelleyNetworkClock.forNetwork("cardano:preprod", null));
    }

    /** Signs with the same key TestTx.SELLER_ADDRESS is derived from (seed 33). */
    private static final MasumiTestSeller SELLER =
            new MasumiTestSeller("33");

    /** The seller-signed terms, in the spec's nested shape. */
    static Map<String, Object> defaultTerms() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("version", "1");
        t.put("paymentType", "Web3CardanoV2");
        t.put("sellerAddress", TestTx.SELLER_ADDRESS);
        t.put("sellerNonce", TestTx.MASUMI_SELLER_NONCE);
        t.put("buyerNonce", TestTx.MASUMI_IDENTIFIER_FROM_PURCHASER);
        t.put("agentIdentifier", TestTx.MASUMI_AGENT_IDENTIFIER);
        t.put("inputHash", TestTx.MASUMI_INPUT_HASH);
        t.put("payByTime", TestTx.MASUMI_PAY_BY_TIME.toString());
        t.put("submitResultTime", TestTx.MASUMI_SUBMIT_RESULT_TIME.toString());
        t.put("unlockTime", TestTx.MASUMI_UNLOCK_TIME.toString());
        t.put("externalDisputeUnlockTime", TestTx.MASUMI_EXTERNAL_DISPUTE_UNLOCK_TIME.toString());
        t.put("settlementPolicy", "l1");
        return t;
    }

    /**
     * A Masumi extra carrying a real seller authorization over its own terms.
     * The signature is produced at test time rather than pasted in, so a test
     * can only pass on terms this seller genuinely consented to.
     */
    static Map<String, Object> defaultExtra() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assetTransferMethod", "masumi");
        m.put("terms", defaultTerms());
        String digest = MasumiDigests.computeTermsDigest(
                MasumiDigests.buildSignedTerms(m, requirements(m)));
        m.put("referenceKey", SELLER.referenceKeyHex());
        m.put("referenceSignature", SELLER.signTermsHex(digest));
        return m;
    }

    /** Builds an extra whose terms carry an extra field, signed over those terms. */
    static Map<String, Object> extraWithTerm(String key, Object value) {
        Map<String, Object> terms = defaultTerms();
        terms.put(key, value);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assetTransferMethod", "masumi");
        m.put("terms", terms);
        String digest = MasumiDigests.computeTermsDigest(
                MasumiDigests.buildSignedTerms(m, requirements(m)));
        m.put("referenceKey", SELLER.referenceKeyHex());
        m.put("referenceSignature", SELLER.signTermsHex(digest));
        return m;
    }

    /**
     * The datum must carry the same reference bytes the terms were signed with.
     * A spec that has deliberately overridden either value keeps its override —
     * that is the test exercising a mismatch, not a fixture to be corrected.
     */
    static TestTx.MasumiSpec signedSpec(TestTx.MasumiSpec spec, Map<String, Object> extra) {
        TestTx.MasumiSpec out = spec;
        if (TestTx.MASUMI_REFERENCE_KEY.equals(spec.referenceKeyHex())) {
            out = out.withReferenceKeyHex(String.valueOf(extra.get("referenceKey")));
        }
        if (TestTx.MASUMI_REFERENCE_SIGNATURE.equals(spec.referenceSignatureHex())) {
            out = out.withReferenceSignatureHex(String.valueOf(extra.get("referenceSignature")));
        }
        return out;
    }

    static PaymentRequirements requirements(Map<String, Object> extra) {
        return new PaymentRequirements("exact", "cardano:preprod", "lovelace",
                TestTx.MASUMI_AMOUNT.toString(), ESCROW, 600, extra);
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
        return verify(TestTx.buildMasumiLockBase64(ESCROW, signedSpec(spec, extra)), extra);
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
        DecodedTransaction decoded = new CardanoTransactionDecoder().decode(
                TestTx.buildMasumiLockBase64(ESCROW, spec));
        int expected = TestTx.buildMasumiDatum(spec).serializeToBytes().length;
        assertThat(decoded.outputs().get(0).inlineDatumRawLen()).isEqualTo(expected).isPositive();
        assertThat(decoded.outputs().get(1).inlineDatumRawLen()).isZero(); // change output
    }

    @Test void rejectsContractMismatch() {
        // `contractAddress` is not a Masumi extra field: payTo carries the escrow
        // and the verifier derives it, so declaring one is a closed-object
        // violation rather than an alternative source of truth.
        Map<String, Object> extra = defaultExtra();
        extra.put("contractAddress", TestTx.SELLER_ADDRESS);
        assertThat(verify(TestTx.MasumiSpec.defaults(), extra).invalidReason())
                .isEqualTo(ErrorCodes.MASUMI_SCHEMA);
    }

    @Test void rejectsMissingDatum() {
        String tx = TestTx.buildBase64(
                TestTx.Spec.defaults().withPayTo(ESCROW).withAmount(TestTx.MASUMI_AMOUNT));
        assertThat(verify(tx, defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_MISSING);
    }

    @Test void rejectsReferenceScript() {
        String tx = TestTx.buildMasumiLockBase64(ESCROW, TestTx.MasumiSpec.defaults(),
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
        String tx = TestTx.buildMasumiLockBase64(ESCROW, TestTx.MasumiSpec.defaults(),
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

    @Test void rejectsEscrowAsBuyerReturnAddress() {
        // A return address pointing back at the escrow (ESCROW) bricks
        // vested_pay's continuation-datum parsing on every spend path.
        assertThat(verify(TestTx.MasumiSpec.defaults().withBuyerReturnAddress(ESCROW), defaultExtra())
                .invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsEscrowAsSellerReturnAddress() {
        // Runs before M7 field matching, so no extra.sellerReturnAddress declaration is needed.
        assertThat(verify(TestTx.MasumiSpec.defaults().withSellerReturnAddress(ESCROW), defaultExtra())
                .invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsDeadlineNoTtl() {
        String tx = TestTx.buildMasumiLockBase64(ESCROW, TestTx.MasumiSpec.defaults(),
                null, false, null);
        assertThat(verify(tx, defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DEADLINE);
    }

    @Test void rejectsDeadlineTtlAfterPayBy() {
        // A far-future TTL slot maps to a POSIX time past pay_by_time. Rule 7's
        // upper bound (TTL within maxTimeoutSeconds of now) is strictly tighter
        // and fires first in the scheme, so this transaction is refused before
        // the Masumi deadline rule is reached. Either way the lock cannot settle
        // past its deadline; assert the rule that actually rejects it.
        String tx = TestTx.buildMasumiLockBase64(ESCROW, TestTx.MasumiSpec.defaults(),
                900_000_000L, false, null);
        assertThat(verify(tx, defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.TTL_TOO_FAR);
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
        assertThat(verify(TestTx.MasumiSpec.defaults().withBuyerAddress(TestTx.THIRD_PARTY_ADDRESS),
                defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_MISMATCH);
    }

    @Test void rejectsSellerMismatch() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withSellerAddress(TestTx.THIRD_PARTY_ADDRESS),
                defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_MISMATCH);
    }

    @Test void rejectsSellerReturnAddressMismatch() {
        // seller_return_address is server-declared, so it must match exactly:
        // the datum carries None while extra declares one.
        Map<String, Object> extra = extraWithTerm("sellerReturnAddress", TestTx.SELLER_ADDRESS);
        assertThat(verify(TestTx.MasumiSpec.defaults(), extra).invalidReason())
                .isEqualTo(ErrorCodes.MASUMI_DATUM_MISMATCH);
    }

    @Test void doesNotMatchBuyerReturnAddress() {
        // buyer_return_address is buyer-supplied: the 402 answers an unauthenticated
        // request, so the server cannot know the payer's refund address. Declaring one
        // that the datum does not carry must NOT reject — the buyer stays pinned by
        // the buyer == payer rule instead.
        // It is not declarable in `extra` at all, so assert the datum-side
        // invariant: a buyer-chosen return address is simply accepted.
        Map<String, Object> extra = defaultExtra();
        assertThat(verify(TestTx.MasumiSpec.defaults().withBuyerReturnAddress(TestTx.PAYER_ADDRESS),
                extra).isValid()).isTrue();
    }

    @Test void rejectsHexFieldMismatch() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withReferenceKeyHex("ffffffff"),
                defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_MISMATCH);
    }

    @Test void rejectsTimeFieldMismatch() {
        assertThat(verify(TestTx.MasumiSpec.defaults().withUnlockTime(new BigInteger("2000001900000")),
                defaultExtra()).invalidReason()).isEqualTo(ErrorCodes.MASUMI_DATUM_MISMATCH);
    }

    @Test void crossImplementationMinUtxoFixture() {
        // Cross-implementation fixture -- must stay in sync with
        // typescript/packages/mechanisms/cardano/test/unit/masumiVerify.test.ts
        // ("cross-implementation min-UTXO fixture"): a 367-byte Evolution-encoded
        // 19-field lock datum at coinsPerUtxoByte=4310.
        assertThat(MasumiConstants.masumiMinUtxoLovelace(367, 0, BigInteger.valueOf(4310)))
                .isEqualTo(BigInteger.valueOf(3_124_750));
        assertThat(MasumiConstants.masumiMinUtxoLovelace(367, 1, BigInteger.valueOf(4310)))
                .isEqualTo(BigInteger.valueOf(3_340_250));
    }
}
