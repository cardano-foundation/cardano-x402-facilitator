package org.cardanofoundation.x402.facilitator.model.verification;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural view of a decoded Cardano transaction, surfacing only the fields
 * the facilitator's verify()/settle() paths need. Port of the TS reference's
 * DecodedCardanoTransaction (utils.ts), plus two spec additions:
 * verifiedWitnessKeyHashes (payer-authorization check D5) and serializedSize
 * (protocol maxTxSize check C3).
 */
public record DecodedTransaction(
        String txHashHex,
        List<String> inputs, // lowercase "txhash#index"
        List<Output> outputs,
        Long ttlSlot, // null = absent
        Long validityStartSlot, // null = absent
        Integer networkId, // null = absent
        int vkeyWitnessCount, // vkey + bootstrap (TS parity)
        int scriptWitnessCount,
        boolean signaturesValid,
        Set<String> verifiedWitnessKeyHashes, // blake2b-224(vkey) hex lowercase
        int serializedSize) {

    public record Output(
            String address,
            BigInteger coin,
            Map<String, BigInteger> assets, // "policyhex.namehex" lowercase
            com.bloxbean.cardano.client.transaction.spec.TransactionOutput raw,
            // Byte length of the on-chain inline-datum CBOR (the wallet's own
            // encoding read from the raw wire bytes), 0 when there is no inline
            // datum. Mirrors the TS reference's datumHex.length/2 — used only by
            // the Masumi M9 post-result min-UTXO estimate, which must not depend
            // on cardano-client-lib's definite-length re-serialization.
            int inlineDatumRawLen) {
    }
}
