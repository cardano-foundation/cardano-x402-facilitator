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
            if (base64Tx == null || base64Tx.length() > 87384) throw new IllegalArgumentException("transaction exceeds 64 KiB");
            raw = Base64.getDecoder().decode(base64Tx);
            if (raw.length > 65536 || !Base64.getEncoder().encodeToString(raw).equals(base64Tx))
                throw new IllegalArgumentException("transaction must be canonical base64 under 64 KiB");
            CborDecoder strict = new CborDecoder(new java.io.ByteArrayInputStream(raw));
            strict.setRejectDuplicateKeys(true);
            strict.setMaxPreallocationSize(65536);
            List<DataItem> wire = strict.decode();
            if (wire.size() != 1 || !(wire.getFirst() instanceof Array envelope)
                    || (envelope.getDataItems().size() != 3 && envelope.getDataItems().size() != 4))
                throw new IllegalArgumentException("invalid transaction envelope");
            if (envelope.getDataItems().size() == 4
                    && !co.nstant.in.cbor.model.SimpleValue.TRUE.equals(envelope.getDataItems().get(2))
                    && !co.nstant.in.cbor.model.SimpleValue.FALSE.equals(envelope.getDataItems().get(2)))
                throw new IllegalArgumentException("is_valid must be boolean");
            validateRawNumbers((co.nstant.in.cbor.model.Map) envelope.getDataItems().getFirst());
            validateAuxiliaryData(raw, envelope);
            tx = Transaction.deserialize(raw);
            txHashHex = TransactionUtil.getTxHash(raw); // blake2b-256(raw body bytes)
        } catch (StackOverflowError e) {
            throw new TransactionDecodeException("Transaction CBOR nesting exceeds decoder budget", e);
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
                Set.copyOf(verifiedKeyHashes), raw.length, body.getFee(), ledgerIsValid(raw), bodyKeys,
                requiredSigners(raw), raw);
    }

    /** Metadata integrity is checked before either local or delegated phase-1 validation. */
    private static void validateAuxiliaryData(byte[] raw, Array envelope) throws Exception {
        var parts = envelope.getDataItems();
        var body = (co.nstant.in.cbor.model.Map) parts.getFirst();
        DataItem commitment = body.get(new UnsignedInteger(7));
        DataItem auxiliary = parts.getLast();
        boolean present = !co.nstant.in.cbor.model.SimpleValue.NULL.equals(auxiliary);
        if (commitment == null && !present) return;
        if (!(commitment instanceof ByteString hash) || commitment.hasTag() || hash.getBytes().length != 32 || !present)
            throw new IllegalArgumentException("auxiliary data and its hash must both be present");

        // Decode preceding envelope items from the original stream to locate the exact
        // auxiliary bytes. Re-encoding a DataItem would change valid noncanonical CBOR.
        var input = new java.io.ByteArrayInputStream(raw);
        int additional = input.read() & 31;
        int headerBytes = additional < 24 || additional == 31 ? 0 : 1 << (additional - 24);
        input.skipNBytes(headerBytes);
        var decoder = new CborDecoder(input);
        for (int i = 0; i < parts.size() - 1; i++) decoder.decodeNext();
        int start = raw.length - input.available();
        decoder.decodeNext();
        byte[] bytes = java.util.Arrays.copyOfRange(raw, start, raw.length - input.available());
        if (!java.security.MessageDigest.isEqual(hash.getBytes(), Blake2bUtil.blake2bHash256(bytes)))
            throw new IllegalArgumentException("auxiliary data hash mismatch");
        validateMetadataUtf8(java.nio.ByteBuffer.wrap(bytes), 0);

        // Support Shelley metadata and Alonzo metadata-only envelopes. Auxiliary
        // script bundles are deliberately unsupported until their full ledger
        // validity rules are implemented; a delegate cannot override this guard.
        if (!(auxiliary instanceof co.nstant.in.cbor.model.Map metadata))
            throw new IllegalArgumentException("unsupported auxiliary data envelope");
        if (metadata.hasTag()) {
            if (metadata.getTag().getValue() != 259 || metadata.getKeys().size() != 1
                    || !(metadata.get(new UnsignedInteger(0)) instanceof co.nstant.in.cbor.model.Map nested))
                throw new IllegalArgumentException("unsupported auxiliary data fields");
            metadata = nested;
        }
        if (metadata.hasTag()) throw new IllegalArgumentException("tagged metadata map");
        for (DataItem label : metadata.getKeys()) {
            if (!(label instanceof UnsignedInteger integer) || label.hasTag() || integer.getValue().bitLength() > 64)
                throw new IllegalArgumentException("invalid metadata label");
            validateMetadatum(metadata.get(label), 0);
        }
    }

    private static void validateMetadatum(DataItem item, int depth) {
        if (depth > 64 || item.hasTag()) throw new IllegalArgumentException("invalid metadata nesting or tag");
        if (item instanceof co.nstant.in.cbor.model.Number integer) {
            BigInteger value = integer.getValue();
            if (value.signum() < 0) value = value.negate().subtract(BigInteger.ONE);
            if (value.bitLength() <= 64) return;
        } else if (item instanceof ByteString bytes) {
            if (bytes.getBytes().length <= 64) return;
        } else if (item instanceof co.nstant.in.cbor.model.UnicodeString text) {
            if (text.getString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64) return;
        } else if (item instanceof Array array) {
            var children = array.getDataItems();
            int count = children.size();
            // co.nstant preserves the terminator of an indefinite-length array.
            if (array.isChunked() && count > 0
                    && co.nstant.in.cbor.model.Special.BREAK.equals(children.getLast())) count--;
            for (int i = 0; i < count; i++) validateMetadatum(children.get(i), depth + 1);
            return;
        } else if (item instanceof co.nstant.in.cbor.model.Map map) {
            for (DataItem key : map.getKeys()) {
                validateMetadatum(key, depth + 1);
                validateMetadatum(map.get(key), depth + 1);
            }
            return;
        }
        throw new IllegalArgumentException("invalid transaction metadatum");
    }

    /** The CBOR library replaces malformed UTF-8; the ledger requires valid text. */
    private static void validateMetadataUtf8(java.nio.ByteBuffer bytes, int depth) throws Exception {
        if (depth > 64) throw new IllegalArgumentException("auxiliary data nesting exceeds budget");
        int header = Byte.toUnsignedInt(bytes.get()), major = header >>> 5, additional = header & 31;
        if (additional == 31) {
            if (major < 2 || major > 5) throw new IllegalArgumentException("invalid indefinite item");
            while (Byte.toUnsignedInt(bytes.get(bytes.position())) != 255) validateMetadataUtf8(bytes, depth + 1);
            bytes.get();
            return;
        }
        long length = additional;
        if (additional >= 24) {
            if (additional > 27) throw new IllegalArgumentException("invalid CBOR header");
            length = 0;
            for (int i = 0; i < (1 << (additional - 24)); i++) length = (length << 8) | Byte.toUnsignedInt(bytes.get());
        }
        if (major == 2 || major == 3) {
            if (length < 0 || length > bytes.remaining()) throw new IllegalArgumentException("invalid string length");
            var contents = bytes.slice(bytes.position(), (int) length);
            if (major == 3) java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(contents);
            bytes.position(bytes.position() + (int) length);
        } else if (major == 4 || major == 5) {
            if (length < 0 || length > bytes.remaining()) throw new IllegalArgumentException("invalid collection length");
            long children = major == 5 ? length * 2 : length;
            for (long i = 0; i < children; i++) validateMetadataUtf8(bytes, depth + 1);
        } else if (major == 6) validateMetadataUtf8(bytes, depth + 1);
    }

    /** CCL truncates several unsigned CBOR integers; reject overflow before typed decoding. */
    private static void validateRawNumbers(co.nstant.in.cbor.model.Map body) {
        var inputs = (Array) body.get(new UnsignedInteger(0));
        for (DataItem item : inputs.getDataItems()) {
            var input = ((Array) item).getDataItems();
            if (input.size() != 2 || ((ByteString) input.getFirst()).getBytes().length != 32)
                throw new IllegalArgumentException("invalid transaction input");
            ((UnsignedInteger) input.get(1)).getValue().intValueExact();
        }
        for (long key : new long[]{3,8}) {
            var item = body.get(new UnsignedInteger(key));
            if (item != null) ((UnsignedInteger) item).getValue().longValueExact();
        }
        var network = body.get(new UnsignedInteger(15));
        if (network != null) {
            int id = ((UnsignedInteger) network).getValue().intValueExact();
            if (id != 0 && id != 1) throw new IllegalArgumentException("invalid network id");
        }
    }

    private static boolean ledgerIsValid(byte[] raw) {
        try {
            var parts = ((Array) CborDecoder.decode(raw).getFirst()).getDataItems();
            return parts.size() == 3 || co.nstant.in.cbor.model.SimpleValue.TRUE.equals(parts.get(2));
        } catch (Exception e) { throw new TransactionDecodeException("invalid validity flag", e); }
    }

    private static Set<String> requiredSigners(byte[] raw) {
        try {
            var body = (co.nstant.in.cbor.model.Map) CborDecoder.decode(TransactionUtil.extractTransactionBodyFromTx(raw)).getFirst();
            DataItem item = body.get(new UnsignedInteger(14));
            if (item == null) return Set.of();
            Set<String> hashes = new HashSet<>();
            for (DataItem signer : ((Array) item).getDataItems()) {
                byte[] hash = ((ByteString) signer).getBytes();
                if (hash.length != 28) throw new IllegalArgumentException("invalid required signer");
                hashes.add(HexUtil.encodeHexString(hash));
            }
            return hashes;
        } catch (Exception e) { throw new TransactionDecodeException("invalid required signers", e); }
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
                if (!(k instanceof UnsignedInteger u) || k.hasTag())
                    throw new IllegalArgumentException("body key must be an untagged integer");
                keys.add(u.getValue().longValueExact());
            }
            return keys;
        } catch (Exception e) {
            throw new TransactionDecodeException("Transaction body CBOR map decode failed", e);
        }
    }
}
