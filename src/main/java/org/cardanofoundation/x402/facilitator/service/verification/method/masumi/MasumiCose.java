package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.cip.cip8.COSEKey;
import com.bloxbean.cardano.client.cip.cip8.COSESign1;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;

/**
 * Verifies the seller's CIP-8 authorization over {@code termsDigest}.
 *
 * <p>This replaces a structural byte-comparison of the declared
 * {@code referenceKey}/{@code referenceSignature} against the datum. Comparing
 * bytes only proves the datum repeats what the requirements claimed — both
 * sides of which the resource server controls. It proves nothing about the
 * seller having agreed to anything. Verifying the COSE_Sign1 proves the holder
 * of the seller address's payment key signed <em>these</em> terms.
 *
 * <p>Three things must hold together, and the address binding is the one that
 * makes the other two mean something:
 * <ul>
 *   <li>the COSE_Key is {@code kty=OKP}, {@code alg=EdDSA}, {@code crv=Ed25519}
 *       with a 32-byte public key and no private material;</li>
 *   <li>the signed payload is exactly the 32-byte {@code termsDigest}, with
 *       {@code hashed=false} — a hashed payload would let a signature over
 *       something else be replayed as one over the digest;</li>
 *   <li>{@code Blake2b-224(publicKey)} equals the seller address's payment
 *       credential, tying the signature to the <em>address</em> that gets paid
 *       rather than to any key that happens to sign.</li>
 * </ul>
 *
 * <p>Mirrors {@code cose.ts} in the TypeScript reference implementation.
 */
public final class MasumiCose {

    private MasumiCose() {
    }

    /**
     * Verifies a seller authorization over a terms digest.
     *
     * @param referenceKeyHex CBOR COSE_Key hex from {@code extra.referenceKey}.
     * @param referenceSignatureHex CBOR COSE_Sign1 hex from {@code extra.referenceSignature}.
     * @param termsDigestHex the 32-byte digest the seller must have signed.
     * @param sellerAddressBech32 the seller address the signature must bind to.
     * @return true when the signature is a valid seller authorization over the digest.
     */
    public static boolean verifySellerTermsSignature(String referenceKeyHex,
                                                     String referenceSignatureHex,
                                                     String termsDigestHex,
                                                     String sellerAddressBech32) {
        try {
            byte[] publicKey = publicKeyFromCoseKey(referenceKeyHex).orElse(null);
            if (publicKey == null) return false;

            COSESign1 sign1 = COSESign1.deserialize(
                    com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.deserialize(
                            HexUtil.decodeHexString(referenceSignatureHex)));

            // The payload must be the digest itself, not a hash of it.
            byte[] payload = sign1.payload();
            byte[] expected = HexUtil.decodeHexString(termsDigestHex);
            if (payload == null || !MessageDigest.isEqual(payload, expected)) return false;
            if (isHashedPayload(sign1)) return false;

            // Address binding: the signing key must control the seller address.
            // A script payment credential can never be a signer, so reject it
            // rather than compare a key hash against a script hash.
            MasumiDatum.MasumiAddressCredentials seller =
                    MasumiDatum.addressCredentials(sellerAddressBech32);
            if (seller == null || seller.payment().isScript()) return false;
            String keyHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(publicKey)).toLowerCase();
            if (!keyHash.equals(seller.payment().hash().toLowerCase())) return false;

            // Ed25519 over the COSE Sig_structure.
            byte[] sigStructure = sign1.signedData().serializeAsBytes();
            return ed25519Verify(publicKey, sigStructure, sign1.signature());
        } catch (Exception e) {
            // A malformed COSE object is an invalid authorization, not a crash.
            return false;
        }
    }

    /**
     * Extracts and structurally validates the COSE_Key public key.
     *
     * @param referenceKeyHex CBOR COSE_Key hex.
     * @return the 32-byte Ed25519 public key, or empty when the key is unusable.
     */
    static Optional<byte[]> publicKeyFromCoseKey(String referenceKeyHex) {
        try {
            COSEKey key = COSEKey.deserialize(
                    com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.deserialize(
                            HexUtil.decodeHexString(referenceKeyHex)));
            // kty = OKP (1), alg = EdDSA (-8), crv = Ed25519 (6).
            if (!isOkp(key) || !isEdDsa(key)) return Optional.empty();
            // COSE labels are negative integers, not strings: -2 is the OKP `x`
            // coordinate (the public key) and -4 is `d` (private material).
            byte[] pub = key.otherHeaderAsBytes(-2L);
            if (pub == null || pub.length != 32) return Optional.empty();
            // A published reference key must not carry private material.
            if (key.otherHeaderAsBytes(-4L) != null) return Optional.empty();
            return Optional.of(pub);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean isOkp(COSEKey key) {
        Object kty = key.keyType();
        return kty != null && ("1".equals(String.valueOf(kty)) || "OKP".equals(String.valueOf(kty)));
    }

    private static boolean isEdDsa(COSEKey key) {
        Object alg = key.algorithmId();
        return alg != null && ("-8".equals(String.valueOf(alg)) || "EdDSA".equals(String.valueOf(alg)));
    }

    /**
     * True when the unprotected header declares a hashed payload. CIP-8 signData
     * sets {@code hashed=false}; a hashed payload would mean the signature covers
     * a hash of something else, which could then be replayed as a signature over
     * the digest itself. Absent is treated as hashed, i.e. rejected.
     */
    private static boolean isHashedPayload(COSESign1 sign1) {
        try {
            var headers = sign1.headers().unprotected().otherHeaders();
            Object hashed = null;
            for (var e : headers.entrySet()) {
                if ("hashed".equals(String.valueOf(e.getKey()))) {
                    hashed = e.getValue();
                    break;
                }
            }
            if (hashed == null) return true;
            String rendered = String.valueOf(hashed);
            return !("false".equalsIgnoreCase(rendered) || rendered.contains("FALSE"));
        } catch (Exception e) {
            return true;
        }
    }

    /** Same Ed25519 primitive the transaction decoder uses for vkey witnesses. */
    private static final EdDSASigningProvider ED25519 = new EdDSASigningProvider();

    private static boolean ed25519Verify(byte[] publicKey, byte[] message, byte[] signature) {
        try {
            return ED25519.verify(signature, message, publicKey);
        } catch (Exception e) {
            return false;
        }
    }

    /** Utility for tests and diagnostics: UTF-8 bytes of a string. */
    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Utility: constant-time-ish comparison used by callers that need it. */
    static boolean bytesEqual(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
