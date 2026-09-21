package org.cardanofoundation.x402.facilitator.service.verification;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.service.verification.method.DefaultTransferVerifier;
import org.cardanofoundation.x402.facilitator.testutil.FakeChainService;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class AuxiliaryDataVerificationTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsMissingAuxiliaryData(boolean delegated) throws Exception {
        assertMetadata(delegated, null, new byte[32], ErrorCodes.DECODE_FAILED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsUnexpectedAuxiliaryData(boolean delegated) throws Exception {
        assertMetadata(delegated, validMetadata(), null, ErrorCodes.DECODE_FAILED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsMismatchedAuxiliaryData(boolean delegated) throws Exception {
        assertMetadata(delegated, validMetadata(), new byte[32], ErrorCodes.DECODE_FAILED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsOversizedMetadataText(boolean delegated) throws Exception {
        byte[] malformed = encode(new Map().put(new UnsignedInteger(1), new UnicodeString("é".repeat(33))));
        assertMetadata(delegated, malformed, Blake2bUtil.blake2bHash256(malformed), ErrorCodes.DECODE_FAILED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptsCorrectlyCommittedMetadata(boolean delegated) throws Exception {
        byte[] metadata = validMetadata();
        assertMetadata(delegated, metadata, Blake2bUtil.blake2bHash256(metadata), null);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void hashesExactWireEncodingOfMetadata(boolean delegated) throws Exception {
        // Indefinite map, non-minimal integer encoding: semantically {1: "ok"}.
        byte[] metadata = HexUtil.decodeHexString("bf1801626f6bff");
        assertMetadata(delegated, metadata, Blake2bUtil.blake2bHash256(metadata), null);
        assertMetadata(delegated, metadata, Blake2bUtil.blake2bHash256(validMetadata()), ErrorCodes.DECODE_FAILED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptsTaggedMetadata(boolean delegated) throws Exception {
        Map metadata = new Map().put(new UnsignedInteger(0), new Map().put(new UnsignedInteger(1), new UnicodeString("ok")));
        metadata.setTag(259);
        byte[] bytes = encode(metadata);
        assertMetadata(delegated, bytes, Blake2bUtil.blake2bHash256(bytes), null);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsMalformedMetadataValues(boolean delegated) throws Exception {
        for (String hex : List.of(
                "a101f5", // booleans are not metadata
                "a101c001", // tagged integer
                "a10161ff", // invalid UTF-8
                "a1616101", // text label
                "a1015841" + "00".repeat(65), // oversized bytes
                "a101c249010000000000000000", // positive integer outside uint64
                "d90103a10180")) { // auxiliary script bundle: unsupported
            byte[] bytes = HexUtil.decodeHexString(hex);
            assertMetadata(delegated, bytes, Blake2bUtil.blake2bHash256(bytes), ErrorCodes.DECODE_FAILED);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptsNestedMetadataAndBoundaryValues(boolean delegated) throws Exception {
        for (String hex : List.of("a1019f01ff", // indefinite metadata list
                "a101a141001bffffffffffffffff", // bytes map key, uint64 maximum
                "a1013bffffffffffffffff", // negative integer minimum
                "a1015840" + "00".repeat(64))) {
            byte[] bytes = HexUtil.decodeHexString(hex);
            assertMetadata(delegated, bytes, Blake2bUtil.blake2bHash256(bytes), null);
        }
    }

    @org.junit.jupiter.api.Test
    void rejectsTaggedAuxiliaryCommitment() throws Exception {
        byte[] metadata = validMetadata();
        var envelope = (Array) CborDecoder.decode(Base64.getDecoder().decode(
                TestTx.buildBase64(TestTx.Spec.defaults()))).getFirst();
        var hash = new ByteString(Blake2bUtil.blake2bHash256(metadata));
        hash.setTag(24);
        ((Map) envelope.getDataItems().getFirst()).put(new UnsignedInteger(7), hash);
        envelope.getDataItems().set(3, CborDecoder.decode(metadata).getFirst());
        String transaction = Base64.getEncoder().encodeToString(encode(envelope));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CardanoTransactionDecoder().decode(transaction))
                .isInstanceOf(CardanoTransactionDecoder.TransactionDecodeException.class);
    }

    @org.junit.jupiter.api.Test
    void rejectsTaggedBodyKeyEvenWithoutAuxiliaryEnvelope() throws Exception {
        var envelope = (Array) CborDecoder.decode(Base64.getDecoder().decode(
                TestTx.buildBase64(TestTx.Spec.defaults()))).getFirst();
        var key = new UnsignedInteger(7);
        key.setTag(24);
        ((Map) envelope.getDataItems().getFirst()).put(key, new ByteString(new byte[32]));
        String transaction = Base64.getEncoder().encodeToString(encode(envelope));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CardanoTransactionDecoder().decode(transaction))
                .isInstanceOf(CardanoTransactionDecoder.TransactionDecodeException.class);
    }

    private static byte[] validMetadata() throws Exception {
        return encode(new Map().put(new UnsignedInteger(1), new UnicodeString("ok")));
    }

    private static byte[] encode(DataItem item) throws Exception {
        var bytes = new ByteArrayOutputStream();
        new CborEncoder(bytes).encode(item);
        return bytes.toByteArray();
    }

    private static void assertMetadata(boolean delegated, byte[] auxiliary, byte[] hash, String error) throws Exception {
        var tx = Transaction.deserialize(Base64.getDecoder().decode(TestTx.buildBase64(TestTx.Spec.defaults().unsigned())));
        if (hash != null) tx.getBody().setAuxiliaryDataHash(hash);
        if (delegated) tx.getBody().setReferenceInputs(List.of(
                new com.bloxbean.cardano.client.transaction.spec.TransactionInput("cd".repeat(32), 0)));
        tx = TransactionSigner.INSTANCE.sign(tx, TestTx.PAYER_KEY);
        var envelope = (Array) CborDecoder.decode(tx.serialize()).getFirst();
        var wire = new ByteArrayOutputStream();
        wire.write(0x84);
        for (int i = 0; i < 3; i++) wire.write(encode(envelope.getDataItems().get(i)));
        wire.write(auxiliary == null ? new byte[]{(byte) 0xf6} : auxiliary);
        var chain = new FakeChainService();
        chain.currentSlot = 999_700;
        chain.unspent.put(TestTx.NONCE, TestTx.PAYER_ADDRESS);
        var scheme = new ExactCardanoScheme(chain, chain, new CardanoTransactionDecoder(),
                List.of(new DefaultTransferVerifier()), 32768,
                ShelleyNetworkClock.forNetwork("cardano:preprod", null),
                delegated ? (decoded, inputs, params, network) -> Optional.empty() : null);
        var req = new PaymentRequirements("exact", "cardano:preprod", "lovelace", "2000000", TestTx.PAY_TO,
                600, java.util.Map.of("assetTransferMethod", "default"));
        var payload = new PaymentPayload(2, null, req, java.util.Map.of("transaction",
                Base64.getEncoder().encodeToString(wire.toByteArray()), "nonce", TestTx.NONCE), null);
        if (error == null) new CardanoTransactionDecoder().decode(Base64.getEncoder().encodeToString(wire.toByteArray()));
        var result = scheme.verify(payload, req);
        assertThat(result.invalidReason()).isEqualTo(error);
        assertThat(result.isValid()).isEqualTo(error == null);
    }
}
