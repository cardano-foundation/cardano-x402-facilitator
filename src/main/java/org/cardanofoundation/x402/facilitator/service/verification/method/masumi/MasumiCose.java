package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider;
import com.bloxbean.cardano.client.util.HexUtil;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;

/** Strict CIP-8 authorization over the exact seller terms, including protected address and key identity. */
public final class MasumiCose {
    private static final EdDSASigningProvider ED25519 = new EdDSASigningProvider();
    private MasumiCose() { }

    public static boolean verifySellerTermsSignature(String referenceKeyHex, String referenceSignatureHex,
                                                     String termsDigestHex, String sellerAddressBech32) {
        try {
            var key = keyMap(referenceKeyHex);
            byte[] publicKey = validatedPublicKey(key);
            if (publicKey == null) return false;
            DataItem decoded = decodeOne(HexUtil.decodeHexString(referenceSignatureHex));
            if (!(decoded instanceof Array array) || array.getDataItems().size() != 4) return false;
            var fields = array.getDataItems();
            byte[] protectedBytes = bytes(fields.get(0));
            if (protectedBytes == null) return false;
            if (!(decodeOne(protectedBytes) instanceof co.nstant.in.cbor.model.Map headers)
                    || !(fields.get(1) instanceof co.nstant.in.cbor.model.Map unprotected)) return false;
            if (!new NegativeInteger(-8).equals(headers.get(label(1)))) return false;
            if (!SimpleValue.FALSE.equals(unprotected.get(new UnicodeString("hashed")))) return false;
            byte[] protectedAddress = bytes(headers.get(new UnicodeString("address")));
            if (protectedAddress == null || !Arrays.equals(protectedAddress, new Address(sellerAddressBech32).getBytes())) return false;

            DataItem keyKid = key.get(label(2));
            DataItem signatureKid = headers.get(label(4));
            if (keyKid != null && bytes(keyKid) == null || signatureKid != null && bytes(signatureKid) == null) return false;
            if (keyKid != null && signatureKid != null && !Arrays.equals(bytes(keyKid), bytes(signatureKid))) return false;

            byte[] expected = HexUtil.decodeHexString(termsDigestHex);
            byte[] payload = bytes(fields.get(2));
            byte[] signature = bytes(fields.get(3));
            if (expected.length != 32 || payload == null || !MessageDigest.isEqual(expected, payload)
                    || signature == null || signature.length != 64) return false;
            var seller = MasumiDatum.addressCredentials(sellerAddressBech32);
            if (seller.payment().isScript()) return false;
            String hash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(publicKey));
            if (!hash.equals(seller.payment().hash())) return false;

            // Preserve the exact protected-header bytes signed by the wallet.
            Array sigStructure = new Array().add(new UnicodeString("Signature1"))
                    .add(new ByteString(protectedBytes)).add(new ByteString(new byte[0])).add(new ByteString(payload));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new CborEncoder(out).encode(sigStructure);
            return ED25519.verify(signature, out.toByteArray(), publicKey);
        } catch (Exception e) {
            return false;
        }
    }

    static Optional<byte[]> publicKeyFromCoseKey(String referenceKeyHex) {
        try { return Optional.ofNullable(validatedPublicKey(keyMap(referenceKeyHex))); }
        catch (Exception e) { return Optional.empty(); }
    }

    private static co.nstant.in.cbor.model.Map keyMap(String hex) throws Exception {
        DataItem decoded = decodeOne(HexUtil.decodeHexString(hex));
        return decoded instanceof co.nstant.in.cbor.model.Map map ? map : null;
    }

    private static byte[] validatedPublicKey(co.nstant.in.cbor.model.Map key) {
        if (key == null || !new UnsignedInteger(1).equals(key.get(label(1)))
                || !new NegativeInteger(-8).equals(key.get(label(3)))
                || !new UnsignedInteger(6).equals(key.get(label(-1)))
                || key.getKeys().contains(label(-4))) return null;
        byte[] pub = bytes(key.get(label(-2)));
        return pub != null && pub.length == 32 ? pub : null;
    }

    private static DataItem decodeOne(byte[] bytes) throws Exception {
        var items = CborDecoder.decode(bytes);
        return items.size() == 1 ? items.get(0) : null;
    }

    private static DataItem label(long value) {
        return value < 0 ? new NegativeInteger(value) : new UnsignedInteger(value);
    }

    private static byte[] bytes(DataItem value) {
        return value instanceof ByteString b ? b.getBytes() : null;
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static boolean bytesEqual(byte[] a, byte[] b) { return Arrays.equals(a, b); }
}
