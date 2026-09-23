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

    @Test void rawInputIndexCannotWrapToNonceZero() throws Exception {
        assertRawMutationRejected(body -> {
            var input = (co.nstant.in.cbor.model.Array) ((co.nstant.in.cbor.model.Array) body.get(new co.nstant.in.cbor.model.UnsignedInteger(0))).getDataItems().getFirst();
            input.getDataItems().set(1,new co.nstant.in.cbor.model.UnsignedInteger(new BigInteger("4294967296")));
        });
    }
    @Test void rawNetworkIdCannotWrapToTestnet() throws Exception {
        assertRawMutationRejected(body -> body.put(new co.nstant.in.cbor.model.UnsignedInteger(15),
                new co.nstant.in.cbor.model.UnsignedInteger(new BigInteger("4294967296"))));
    }
    @Test void duplicateBodyKeysAreRejected() throws Exception {
        byte[] raw = Base64.getDecoder().decode(TestTx.buildBase64(TestTx.Spec.defaults().unsigned()));
        byte[] body = TransactionUtil.extractTransactionBodyFromTx(raw);
        assertThat(body[0] & 255).isBetween(160,182);
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write(raw[0]); bytes.write(body[0]+1); bytes.write(body,1,body.length-1);
        bytes.write(2); bytes.write(1); // duplicate fee key, hidden by permissive map decoding
        bytes.write(raw,1+body.length,raw.length-1-body.length);
        assertThatThrownBy(() -> decoder.decode(Base64.getEncoder().encodeToString(bytes.toByteArray())))
                .isInstanceOf(CardanoTransactionDecoder.TransactionDecodeException.class);
    }
    private void assertRawMutationRejected(java.util.function.Consumer<co.nstant.in.cbor.model.Map> mutate) throws Exception {
        var envelope = (co.nstant.in.cbor.model.Array) co.nstant.in.cbor.CborDecoder.decode(
                Base64.getDecoder().decode(TestTx.buildBase64(TestTx.Spec.defaults().unsigned()))).getFirst();
        mutate.accept((co.nstant.in.cbor.model.Map) envelope.getDataItems().getFirst());
        var bytes = new java.io.ByteArrayOutputStream(); new co.nstant.in.cbor.CborEncoder(bytes).encode(envelope);
        assertThatThrownBy(() -> decoder.decode(Base64.getEncoder().encodeToString(bytes.toByteArray())))
                .isInstanceOf(CardanoTransactionDecoder.TransactionDecodeException.class);
    }
}
