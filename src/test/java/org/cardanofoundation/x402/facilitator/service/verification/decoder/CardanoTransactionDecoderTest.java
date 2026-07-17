package org.cardanofoundation.x402.facilitator.service.verification.decoder;

import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class CardanoTransactionDecoderTest {
    private final CardanoTransactionDecoder decoder = new CardanoTransactionDecoder();

    @Test void decodesSignedPayment() {
        String b64 = TestTx.buildBase64(TestTx.Spec.defaults());
        DecodedTransaction d = decoder.decode(b64);
        assertThat(d.inputs()).containsExactly(TestTx.NONCE);
        assertThat(d.outputs().get(0).address()).isEqualTo(TestTx.PAY_TO);
        assertThat(d.outputs().get(0).coin()).isEqualTo(BigInteger.valueOf(2_000_000L));
        assertThat(d.ttlSlot()).isEqualTo(1_000_000L);
        assertThat(d.validityStartSlot()).isNull();
        assertThat(d.networkId()).isNull();
        assertThat(d.vkeyWitnessCount()).isEqualTo(1);
        assertThat(d.signaturesValid()).isTrue();
        // txHash must equal the raw-bytes hash cardano-client-lib computes:
        assertThat(d.txHashHex())
                .isEqualTo(TransactionUtil.getTxHash(Base64.getDecoder().decode(b64)));
    }

    @Test void flagsUnsigned() {
        DecodedTransaction d = decoder.decode(TestTx.buildBase64(TestTx.Spec.defaults().unsigned()));
        assertThat(d.vkeyWitnessCount()).isZero();
        assertThat(d.scriptWitnessCount()).isZero();
    }

    @Test void flagsBadSignature() {
        DecodedTransaction d = decoder.decode(TestTx.buildBase64WithBadSignature());
        assertThat(d.signaturesValid()).isFalse();
    }

    @Test void exposesNetworkIdWhenPresent() {
        DecodedTransaction d = decoder.decode(
                TestTx.buildBase64(TestTx.Spec.defaults().withNetworkId(NetworkId.TESTNET)));
        assertThat(d.networkId()).isZero();
    }

    @Test void throwsOnGarbage() {
        assertThatThrownBy(() -> decoder.decode(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})))
                .isInstanceOf(CardanoTransactionDecoder.TransactionDecodeException.class);
    }

    @Test void ttlZeroIsPresentNotAbsent() {
        DecodedTransaction d = decoder.decode(TestTx.buildBase64TtlZero());
        assertThat(d.ttlSlot()).isZero(); // present => verify() must report ttl_expired
        DecodedTransaction noTtl = decoder.decode(TestTx.buildBase64(TestTx.Spec.defaults().withTtl(null)));
        assertThat(noTtl.ttlSlot()).isNull();
    }
}
