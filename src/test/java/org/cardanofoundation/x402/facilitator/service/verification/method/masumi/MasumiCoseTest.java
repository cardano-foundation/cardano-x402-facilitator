package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies a genuine CIP-8 seller authorization produced by the TypeScript
 * reference implementation, and confirms Java reconstructs the same digest.
 *
 * <p>The previous implementation compared the declared referenceKey and
 * referenceSignature bytes against the datum. A resource server controls both
 * sides of that comparison, so it proved nothing about seller consent. These
 * assertions prove the Java facilitator accepts exactly what the reference
 * seller signs, and refuses the rest.
 */
class MasumiCoseTest {

    private static final String SELLER = "addr_test1vpt780ulj0qpqs72xwftrvkfuztqxgr43zqk3j4m3x4tndg6qr3hs";
    private static final String REFERENCE_KEY = "a401010327200621582017cb79fb2b4120f2b1ec65e4198d6e08b28e813feb01e4a400839b85e18080ce";
    private static final String REFERENCE_SIGNATURE = "84582aa201276761646472657373581d6057e3bf9f93c01043ca3392b1b2c9e096032075888168cabb89aab9b5a166686173686564f458203b33aabb96cea60b86f35425717666b5a969584f738a856c0b0d8887c300858958405621924f2a50d0fa1fcc3517500019c16bf4a149c2288f6b813027bd9078dc8df9c36f7509e0d5c418f94e2d8cf79c04e5e8e209fbb1bb3f47bfc186ce167b0a";
    private static final String PAY_TO = "addr_test1wzs4e6wc95hkwezlccjw9mdvq0r0rsgx6zk34avptga3ftgn37w4g";
    /** termsDigest the TypeScript implementation computed for these terms. */
    private static final String EXPECTED_DIGEST = "3b33aabb96cea60b86f35425717666b5a969584f738a856c0b0d8887c3008589";

    private static Map<String, Object> extra() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("version", "1");
        t.put("paymentType", "Web3CardanoV2");
        t.put("sellerAddress", "addr_test1vpt780ulj0qpqs72xwftrvkfuztqxgr43zqk3j4m3x4tndg6qr3hs");
        t.put("sellerNonce", "0000000000000000000000000000000000000000000000000000000000000004");
        t.put("buyerNonce", "");
        t.put("inputHash", "a04e62604bb67a7e1dc06926a0d6d040fd8d60cc28b2cb5fb94afcfff5d7bcea");
        t.put("payByTime", "1789992600000");
        t.put("submitResultTime", "1789993800000");
        t.put("unlockTime", "1789994700000");
        t.put("externalDisputeUnlockTime", "1789995600000");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("assetTransferMethod", "masumi");
        e.put("terms", t);
        return e;
    }

    private static PaymentRequirements requirements(String amount) {
        return new PaymentRequirements("exact", "cardano:preprod", "lovelace",
                amount, PAY_TO, 600, extra());
    }

    private static String termsDigest(PaymentRequirements r) {
        return MasumiDigests.computeTermsDigest(MasumiDigests.buildSignedTerms(r.extra(), r));
    }

    @Test
    void reconstructsTheSameTermsDigestAsTypeScript() {
        assertThat(termsDigest(requirements("3000000"))).isEqualTo(EXPECTED_DIGEST);
    }

    @Test
    void acceptsAGenuineSellerAuthorization() {
        assertThat(MasumiCose.verifySellerTermsSignature(
                REFERENCE_KEY, REFERENCE_SIGNATURE, EXPECTED_DIGEST, SELLER)).isTrue();
    }

    @Test
    void rejectsASignatureReQuotedAtADifferentPrice() {
        // The price is projected into the signed digest, so the same seller
        // signature cannot be reused to demand more.
        assertThat(MasumiCose.verifySellerTermsSignature(
                REFERENCE_KEY, REFERENCE_SIGNATURE, termsDigest(requirements("9000000")), SELLER))
                .isFalse();
    }

    @Test
    void rejectsWhenTheKeyDoesNotControlTheSellerAddress() {
        String otherSeller = "addr_test1vrdhewmpp96gv6az4vymy80hlw9082sjz6rylt2srpntsdq6njxxu";
        assertThat(MasumiCose.verifySellerTermsSignature(
                REFERENCE_KEY, REFERENCE_SIGNATURE, EXPECTED_DIGEST, otherSeller)).isFalse();
    }

    @Test
    void rejectsMalformedCoseObjects() {
        assertThat(MasumiCose.verifySellerTermsSignature(
                "a10101", REFERENCE_SIGNATURE, EXPECTED_DIGEST, SELLER)).isFalse();
        assertThat(MasumiCose.verifySellerTermsSignature(
                REFERENCE_KEY, "deadbeef", EXPECTED_DIGEST, SELLER)).isFalse();
    }
    @Test void rejectsWrongCurveEvenWhenTheSignatureIsValid() {
        String wrongCurve = REFERENCE_KEY.replace("2006", "2007");
        assertThat(MasumiCose.verifySellerTermsSignature(
                wrongCurve, REFERENCE_SIGNATURE, EXPECTED_DIGEST, SELLER)).isFalse();
    }

    @Test void rejectsCryptographicallyValidSignaturesWithWrongProtectedHeaders() {
        var seller = new org.cardanofoundation.x402.facilitator.testutil.MasumiTestSeller("77");
        byte[] address = new com.bloxbean.cardano.client.address.Address(seller.sellerAddress).getBytes();
        for (var header : java.util.List.of(
                new com.bloxbean.cardano.client.cip.cip8.HeaderMap().algorithmId(-7L).addOtherHeader("address", address),
                new com.bloxbean.cardano.client.cip.cip8.HeaderMap().algorithmId(-8L),
                new com.bloxbean.cardano.client.cip.cip8.HeaderMap().algorithmId(-8L).addOtherHeader("address", new byte[29]))) {
            assertThat(MasumiCose.verifySellerTermsSignature(seller.referenceKeyHex(),
                    seller.signTermsHex(EXPECTED_DIGEST, header), EXPECTED_DIGEST, seller.sellerAddress)).isFalse();
        }
    }

    @Test void requiresMatchingKeyIdentifiersWhenBothArePresent() {
        var seller = new org.cardanofoundation.x402.facilitator.testutil.MasumiTestSeller("77");
        var key = com.bloxbean.cardano.client.cip.cip8.COSEKey.deserialize(
                com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.deserialize(
                        com.bloxbean.cardano.client.util.HexUtil.decodeHexString(seller.referenceKeyHex())));
        key.keyId(new byte[] {1});
        String keyHex = com.bloxbean.cardano.client.util.HexUtil.encodeHexString(key.serializeAsBytes());
        var header = new com.bloxbean.cardano.client.cip.cip8.HeaderMap().algorithmId(-8L)
                .addOtherHeader("address", new com.bloxbean.cardano.client.address.Address(seller.sellerAddress).getBytes())
                .keyId(new byte[] {2});
        assertThat(MasumiCose.verifySellerTermsSignature(keyHex, seller.signTermsHex(EXPECTED_DIGEST, header),
                EXPECTED_DIGEST, seller.sellerAddress)).isFalse();
        header.keyId(new byte[] {1});
        assertThat(MasumiCose.verifySellerTermsSignature(keyHex, seller.signTermsHex(EXPECTED_DIGEST, header),
                EXPECTED_DIGEST, seller.sellerAddress)).isTrue();
    }

}
