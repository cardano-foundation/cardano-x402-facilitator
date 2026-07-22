package org.cardanofoundation.x402.facilitator.service.verification.decoder;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decodes a base64 CBOR Cardano transaction into the view verify() needs.
 * CORRECTNESS: the tx hash / signature message is blake2b-256 over the RAW
 * body bytes from the wire — TransactionUtil extracts them without
 * re-serialization.
 */
@Component
public class CardanoTransactionDecoder {

    public static class TransactionDecodeException extends RuntimeException {
        public TransactionDecodeException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    private final EdDSASigningProvider ed25519 = new EdDSASigningProvider();

    public DecodedTransaction decode(String base64Tx) {
        byte[] raw;
        Transaction tx;
        String txHashHex;
        try {
            raw = Base64.getDecoder().decode(base64Tx);
            tx = Transaction.deserialize(raw);
            txHashHex = TransactionUtil.getTxHash(raw); // blake2b-256(raw body bytes)
        } catch (Exception e) {
            throw new TransactionDecodeException("Transaction CBOR decode failed", e);
        }

        TransactionBody body = tx.getBody();

        List<String> inputs = body.getInputs().stream()
                .map(i -> i.getTransactionId().toLowerCase() + "#" + i.getIndex())
                .toList();

        // On-chain inline-datum byte length per output, read from the raw wire
        // bytes (the wallet's own CBOR), aligned by output index.
        int[] inlineDatumLens = inlineDatumLengths(raw, body.getOutputs().size());
        List<DecodedTransaction.Output> outputs = new ArrayList<>();
        int outIdx = 0;
        for (TransactionOutput out : body.getOutputs()) {
            Map<String, BigInteger> assets = new HashMap<>();
            if (out.getValue().getMultiAssets() != null) {
                for (MultiAsset ma : out.getValue().getMultiAssets()) {
                    for (Asset a : ma.getAssets()) {
                        String nameHex = a.getNameAsHex();
                        if (nameHex.startsWith("0x")) nameHex = nameHex.substring(2);
                        assets.put((ma.getPolicyId() + "." + nameHex).toLowerCase(), a.getValue());
                    }
                }
            }
            outputs.add(new DecodedTransaction.Output(out.getAddress(), out.getValue().getCoin(),
                    assets, out, inlineDatumLens[outIdx++]));
        }

        // cardano-client-lib models ttl/validityStart as primitive longs (0 when
        // absent), but a REAL `ttl: 0` must fail as expired, so detect key
        // presence in the raw body CBOR map: key 3 = ttl, key 8 = validity start.
        Set<Long> bodyKeys = topLevelBodyKeys(raw);
        Long ttl = bodyKeys.contains(3L) ? body.getTtl() : null;
        Long validityStart = bodyKeys.contains(8L) ? body.getValidityStartInterval() : null;
        Integer networkId = body.getNetworkId() == null ? null
                : (body.getNetworkId() == NetworkId.MAINNET ? 1 : 0);

        TransactionWitnessSet ws = tx.getWitnessSet() == null ? new TransactionWitnessSet() : tx.getWitnessSet();
        List<VkeyWitness> vkeys = ws.getVkeyWitnesses() == null ? List.of() : ws.getVkeyWitnesses();
        int bootstrapCount = ws.getBootstrapWitnesses() == null ? 0 : ws.getBootstrapWitnesses().size();
        int scriptWitnessCount = size(ws.getNativeScripts()) + size(ws.getPlutusV1Scripts())
                + size(ws.getPlutusV2Scripts()) + size(ws.getPlutusV3Scripts()) + size(ws.getRedeemers());

        // Every vkey witness must Ed25519-verify over the 32-byte body hash.
        // Vacuously true with zero vkey witnesses — the scheme's UNSIGNED check
        // handles the no-witness case separately. Bootstrap (Byron) witnesses
        // are counted, not verified.
        byte[] bodyHash = HexUtil.decodeHexString(txHashHex);
        boolean signaturesValid = true;
        Set<String> verifiedKeyHashes = new HashSet<>();
        for (VkeyWitness w : vkeys) {
            if (!ed25519.verify(w.getSignature(), bodyHash, w.getVkey())) {
                signaturesValid = false;
                break;
            }
            verifiedKeyHashes.add(HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(w.getVkey())).toLowerCase());
        }
        if (!signaturesValid) verifiedKeyHashes = Set.of();

        return new DecodedTransaction(txHashHex, inputs, outputs, ttl, validityStart, networkId,
                vkeys.size() + bootstrapCount, scriptWitnessCount, signaturesValid,
                Set.copyOf(verifiedKeyHashes), raw.length);
    }

    private static int size(List<?> l) {
        return l == null ? 0 : l.size();
    }

    /**
     * On-chain inline-datum byte lengths per output, in output order, read from
     * the raw transaction CBOR (post-Alonzo map-form outputs: key 1 = outputs,
     * each output map key 2 = datum_option {@code [1, #6.24(bstr .cbor datum)]}).
     * The length is the wrapped datum's own byte count (equivalent to
     * {@code datumHex.length/2}). 0 when an output carries no inline datum
     * (absent, a datum hash, or a legacy array-form output).
     */
    private static int[] inlineDatumLengths(byte[] rawTx, int outputCount) {
        int[] lens = new int[outputCount];
        try {
            byte[] bodyBytes = TransactionUtil.extractTransactionBodyFromTx(rawTx);
            DataItem bodyItem = CborDecoder.decode(bodyBytes).get(0);
            if (!(bodyItem instanceof co.nstant.in.cbor.model.Map body)) return lens;
            DataItem outputsItem = body.get(new UnsignedInteger(1));
            if (!(outputsItem instanceof Array outputs)) return lens;
            List<DataItem> items = outputs.getDataItems();
            for (int i = 0; i < items.size() && i < lens.length; i++) {
                lens[i] = inlineDatumLenOf(items.get(i));
            }
        } catch (Exception e) {
            // Malformed/legacy output encoding — leave zeros; M9 simply won't
            // subtract this precision (the min-UTXO buffers still apply).
            return lens;
        }
        return lens;
    }

    /** Inline-datum byte length of a single output DataItem, or 0 when absent. */
    private static int inlineDatumLenOf(DataItem outputItem) {
        if (!(outputItem instanceof co.nstant.in.cbor.model.Map outMap)) return 0; // legacy array form
        DataItem datumOption = outMap.get(new UnsignedInteger(2));
        if (!(datumOption instanceof Array opt)) return 0;
        List<DataItem> parts = opt.getDataItems();
        if (parts.size() != 2 || !(parts.get(0) instanceof UnsignedInteger kind)
                || kind.getValue().intValue() != 1) {
            return 0; // [0, datum_hash] => not an inline datum
        }
        return parts.get(1) instanceof ByteString bs ? bs.getBytes().length : 0;
    }

    /** Integer keys present in the body map, read from the raw wire bytes. */
    private static Set<Long> topLevelBodyKeys(byte[] rawTx) {
        try {
            byte[] bodyBytes = TransactionUtil.extractTransactionBodyFromTx(rawTx);
            DataItem item = CborDecoder.decode(bodyBytes).get(0);
            Set<Long> keys = new HashSet<>();
            for (DataItem k : ((co.nstant.in.cbor.model.Map) item).getKeys()) {
                if (k instanceof UnsignedInteger u) keys.add(u.getValue().longValue());
            }
            return keys;
        } catch (Exception e) {
            throw new TransactionDecodeException("Transaction body CBOR map decode failed", e);
        }
    }
}
