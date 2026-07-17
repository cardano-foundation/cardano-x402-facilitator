package org.cardanofoundation.x402.facilitator.service.verification.method.script;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
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
 * Full-verify coverage for {@link ScriptTransferVerifier} (the {@code script}
 * assetTransferMethod): S1 address reconstruction and the S3 per-Plutus-version
 * datum policy, driven through {@link ExactCardanoScheme}.
 */
class ScriptTransferVerifierTest {

    static final BigInteger AMOUNT = BigInteger.valueOf(5_000_000L);
    static final PlutusData INLINE = BigIntPlutusData.of(1);
    static final byte[] DATUM_HASH = new byte[32];

    FakeChainService chain;
    ExactCardanoScheme strict;
    ExactCardanoScheme v3Optional;

    @BeforeEach
    void setUp() {
        chain = new FakeChainService();
        chain.unspent.put(TestTx.NONCE, TestTx.PAYER_ADDRESS);
        chain.currentSlot = 500_000L;
        strict = scheme(false);
        v3Optional = scheme(true);
    }

    ExactCardanoScheme scheme(boolean v3DatumOptional) {
        return new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier(), new ScriptTransferVerifier(v3DatumOptional)), 32768);
    }

    static Map<String, Object> inlineExtra(String type, String code) {
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("type", type);
        script.put("code", code);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "script");
        extra.put("script", script);
        return extra;
    }

    static Map<String, Object> hashExtra(String scriptHash) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "script");
        extra.put("scriptHash", scriptHash);
        return extra;
    }

    PaymentRequirements requirements(String payTo, Map<String, Object> extra) {
        return new PaymentRequirements("exact", "cardano:preprod", "lovelace",
                AMOUNT.toString(), payTo, 600, extra);
    }

    PaymentPayload payload(String txB64, PaymentRequirements accepted) {
        Map<String, Object> p = new HashMap<>();
        p.put("transaction", txB64);
        p.put("nonce", TestTx.NONCE);
        return new PaymentPayload(2, null, accepted, p, null);
    }

    VerifyResponse verify(ExactCardanoScheme scheme, String payTo, Map<String, Object> extra,
                          PlutusData inline, byte[] datumHash) {
        PaymentRequirements req = requirements(payTo, extra);
        String tx = TestTx.buildScriptPaymentBase64(payTo, AMOUNT, inline, datumHash);
        return scheme.verify(payload(tx, req), req);
    }

    @Test void happyV3InlineDatum() {
        VerifyResponse r = verify(strict, TestTx.SCRIPT_ADDR_V3,
                inlineExtra("plutusV3", TestTx.SCRIPT_CODE_V3), INLINE, null);
        assertThat(r.isValid()).isTrue();
        assertThat(r.payer()).isEqualTo(TestTx.PAYER_ADDRESS);
    }

    @Test void happyScriptHashOnlyWithDatum() {
        VerifyResponse r = verify(strict, TestTx.SCRIPT_ADDR_V3,
                hashExtra(TestTx.SCRIPT_HASH_V3), INLINE, null);
        assertThat(r.isValid()).isTrue();
    }

    @Test void rejectsAddressMismatch() {
        VerifyResponse r = verify(strict, TestTx.SCRIPT_ADDR_V3,
                hashExtra("00".repeat(28)), INLINE, null);
        assertThat(r.invalidReason()).isEqualTo(ErrorCodes.SCRIPT_ADDRESS_MISMATCH);
    }

    @Test void rejectsMissingDescriptor() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "script");
        VerifyResponse r = verify(strict, TestTx.SCRIPT_ADDR_V3, extra, INLINE, null);
        assertThat(r.invalidReason()).isEqualTo(ErrorCodes.SCRIPT_ADDRESS_MISMATCH);
    }

    @Test void rejectsV3DatumlessUnderStrict() {
        VerifyResponse r = verify(strict, TestTx.SCRIPT_ADDR_V3,
                inlineExtra("plutusV3", TestTx.SCRIPT_CODE_V3), null, null);
        assertThat(r.invalidReason()).isEqualTo(ErrorCodes.SCRIPT_DATUM_MISSING);
    }

    @Test void allowsV3DatumlessUnderOptionalPolicy() {
        VerifyResponse r = verify(v3Optional, TestTx.SCRIPT_ADDR_V3,
                inlineExtra("plutusV3", TestTx.SCRIPT_CODE_V3), null, null);
        assertThat(r.isValid()).isTrue();
    }

    @Test void rejectsV1Datumless() {
        VerifyResponse r = verify(strict, TestTx.SCRIPT_ADDR_V1,
                inlineExtra("plutusV1", TestTx.SCRIPT_CODE_V3), null, null);
        assertThat(r.invalidReason()).isEqualTo(ErrorCodes.SCRIPT_DATUM_MISSING);
    }

    @Test void rejectsV1InlineOnly() {
        // PlutusV1 predates inline datums: only a datum HASH makes the output spendable.
        VerifyResponse r = verify(strict, TestTx.SCRIPT_ADDR_V1,
                inlineExtra("plutusV1", TestTx.SCRIPT_CODE_V3), INLINE, null);
        assertThat(r.invalidReason()).isEqualTo(ErrorCodes.SCRIPT_DATUM_MISSING);
    }

    @Test void acceptsV1WithDatumHash() {
        VerifyResponse r = verify(strict, TestTx.SCRIPT_ADDR_V1,
                inlineExtra("plutusV1", TestTx.SCRIPT_CODE_V3), null, DATUM_HASH);
        assertThat(r.isValid()).isTrue();
    }

    @Test void rejectsV2Datumless() {
        VerifyResponse r = verify(strict, TestTx.SCRIPT_ADDR_V2,
                inlineExtra("plutusV2", TestTx.SCRIPT_CODE_V3), null, null);
        assertThat(r.invalidReason()).isEqualTo(ErrorCodes.SCRIPT_DATUM_MISSING);
    }

    @Test void acceptsV2Inline() {
        VerifyResponse r = verify(strict, TestTx.SCRIPT_ADDR_V2,
                inlineExtra("plutusV2", TestTx.SCRIPT_CODE_V3), INLINE, null);
        assertThat(r.isValid()).isTrue();
    }

    @Test void rejectsScriptHashOnlyDatumlessEvenUnderOptional() {
        // Unknown language (scriptHash-only) => SOME datum required, regardless of v3 policy.
        VerifyResponse r = verify(v3Optional, TestTx.SCRIPT_ADDR_V3,
                hashExtra(TestTx.SCRIPT_HASH_V3), null, null);
        assertThat(r.invalidReason()).isEqualTo(ErrorCodes.SCRIPT_DATUM_MISSING);
    }
}
