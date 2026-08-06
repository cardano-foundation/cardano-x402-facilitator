package org.cardanofoundation.x402.facilitator.testutil;

import co.nstant.in.cbor.model.SimpleValue;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.cip.cip8.COSEKey;
import com.bloxbean.cardano.client.cip.cip8.COSESign1;
import com.bloxbean.cardano.client.cip.cip8.HeaderMap;
import com.bloxbean.cardano.client.cip.cip8.Headers;
import com.bloxbean.cardano.client.cip.cip8.ProtectedHeaderMap;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.SecretKey;
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider;
import com.bloxbean.cardano.client.util.HexUtil;

/**
 * A test seller that produces <em>genuine</em> CIP-8 authorizations.
 *
 * <p>Masumi fixtures cannot use canned bytes any more: the facilitator now
 * verifies the seller's signature over {@code termsDigest} rather than
 * comparing declared bytes against the datum. Signing at test time keeps the
 * fixtures honest — a test only passes if the terms it asserts are terms this
 * seller actually consented to.
 */
public final class MasumiTestSeller {

    /** COSE label for the OKP public key (`x`). */
    private static final long COSE_LABEL_X = -2L;
    /** COSE label for the curve (`crv`); 6 = Ed25519. */
    private static final long COSE_LABEL_CRV = -1L;
    private static final long COSE_ALG_EDDSA = -8L;
    private static final long COSE_KTY_OKP = 1L;
    private static final long COSE_CRV_ED25519 = 6L;

    private static final EdDSASigningProvider ED25519 = new EdDSASigningProvider();

    private final SecretKey secretKey;
    private final byte[] publicKey;

    /** Bech32 enterprise address whose payment credential the key controls. */
    public final String sellerAddress;

    /**
     * Creates a deterministic test seller.
     *
     * @param seedHexByte a repeated byte forming the 32-byte seed, e.g. "77".
     */
    public MasumiTestSeller(String seedHexByte) {
        try {
            this.secretKey = new SecretKey("5820" + seedHexByte.repeat(32));
            this.publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(secretKey).getBytes();
            this.sellerAddress = AddressProvider.getEntAddress(
                    Credential.fromKey(KeyGenUtil.getKeyHash(
                            KeyGenUtil.getPublicKeyFromPrivateKey(secretKey))),
                    Networks.testnet()).toBech32();
        } catch (Exception e) {
            throw new IllegalStateException("failed to derive the test seller key", e);
        }
    }

    /**
     * The published COSE_Key for this seller, as hex.
     *
     * @return CBOR COSE_Key hex suitable for {@code extra.referenceKey}.
     */
    public String referenceKeyHex() {
        COSEKey key = new COSEKey()
                .keyType(COSE_KTY_OKP)
                .algorithmId(COSE_ALG_EDDSA)
                .addOtherHeader(COSE_LABEL_CRV, COSE_CRV_ED25519)
                .addOtherHeader(COSE_LABEL_X, publicKey);
        return HexUtil.encodeHexString(key.serializeAsBytes());
    }

    /**
     * Signs a terms digest the way a CIP-30 wallet's {@code signData} would:
     * the payload is the digest itself and {@code hashed} is false.
     *
     * @param termsDigestHex the 32-byte digest to authorize.
     * @return CBOR COSE_Sign1 hex for {@code extra.referenceSignature}.
     */
    public String signTermsHex(String termsDigestHex) {
        try {
            HeaderMap protectedMap = new HeaderMap().algorithmId(COSE_ALG_EDDSA);
            HeaderMap unprotected = new HeaderMap()
                    .addOtherHeader("hashed", SimpleValue.FALSE);
            Headers headers = new Headers()
                    ._protected(new ProtectedHeaderMap(protectedMap))
                    .unprotected(unprotected);

            COSESign1 sign1 = new COSESign1()
                    .headers(headers)
                    .payload(HexUtil.decodeHexString(termsDigestHex));

            byte[] sigStructure = sign1.signedData().serializeAsBytes();
            byte[] signature = ED25519.sign(sigStructure, secretKey.getBytes());
            return HexUtil.encodeHexString(sign1.signature(signature).serializeAsBytes());
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign the test terms digest", e);
        }
    }
}
