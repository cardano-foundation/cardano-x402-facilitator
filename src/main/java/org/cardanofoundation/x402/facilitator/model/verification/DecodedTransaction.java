package org.cardanofoundation.x402.facilitator.model.verification;

import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural view of a decoded Cardano transaction, surfacing only the fields
 * the facilitator's verify()/settle() paths need, including
 * verifiedWitnessKeyHashes (payer-authorization check D5) and
 * serializedSize (protocol maxTxSize check C3).
 */
public record DecodedTransaction(
        String txHashHex,
        List<String> inputs, // lowercase "txhash#index"
        List<Output> outputs,
        Long ttlSlot, // null = absent
        Long validityStartSlot, // null = absent
        Integer networkId, // null = absent
        int vkeyWitnessCount, // vkey + bootstrap witness count
        int scriptWitnessCount,
        boolean signaturesValid,
        Set<String> verifiedWitnessKeyHashes, // blake2b-224(vkey) hex lowercase
        int serializedSize,
        BigInteger fee,
        boolean isValid,
        Set<Long> bodyKeys,
        Set<String> requiredSignerKeyHashes,
        byte[] rawBytes) {

    public DecodedTransaction {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        verifiedWitnessKeyHashes = Set.copyOf(verifiedWitnessKeyHashes);
        bodyKeys = Set.copyOf(bodyKeys);
        requiredSignerKeyHashes = Set.copyOf(requiredSignerKeyHashes);
        rawBytes = rawBytes == null ? null : rawBytes.clone();
    }
    @Override public byte[] rawBytes() { return rawBytes == null ? null : rawBytes.clone(); }

    /** Legacy construction is for structural consumers; missing fee fails fresh phase-1 checks. */
    public DecodedTransaction(String txHashHex, List<String> inputs, List<Output> outputs,
                              Long ttlSlot, Long validityStartSlot, Integer networkId,
                              int vkeyWitnessCount, int scriptWitnessCount, boolean signaturesValid,
                              Set<String> verifiedWitnessKeyHashes, int serializedSize) {
        this(txHashHex, inputs, outputs, ttlSlot, validityStartSlot, networkId, vkeyWitnessCount,
                scriptWitnessCount, signaturesValid, verifiedWitnessKeyHashes, serializedSize,
                null, true, Set.of(), Set.of(), null);
    }

    public record Output(
            String address,
            BigInteger coin,
            Map<String, BigInteger> assets, // "policyhex.namehex" lowercase
            TransactionOutput raw,
            // Byte length of the on-chain inline-datum CBOR (the wallet's own
            // encoding read from the raw wire bytes), 0 when there is no inline
            // datum. Used only by the Masumi M9 post-result min-UTXO estimate,
            // which must not depend on cardano-client-lib's definite-length
            // re-serialization.
            int inlineDatumRawLen) {
        public Output { assets = Map.copyOf(assets); }
    }
}
