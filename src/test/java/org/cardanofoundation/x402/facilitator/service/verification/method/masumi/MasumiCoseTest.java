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

    private static final String SELLER = "addr_test1qq4jrrcfzylccwgqu3su865es52jkf7yzrdu9cw3z84nycnn3zz9lvqj7vs95tej896xkekzkufhpuk64ja7pga2g8ksdf8km4";
    private static final String REFERENCE_KEY = "a401010327200621582020578a9a8283f754d152e41391de5cb8f9d63f8acb5d71e557f974b1173a9a96";
    private static final String REFERENCE_SIGNATURE = "845846a2012767616464726573735839002b218f09113f8c3900e461c3ea9985152b27c410dbc2e1d111eb32627388845fb012f3205a2f3239746b66c2b71370f2daacbbe0a3aa41eda166686173686564f45820d8275e410352d9277286f660d41536e2efbee5744232e272e606887f8aeb19b75840aa500ea1d094a77c24dbfb0369f6d4da7a13bb8cac8b3d4f36938e0cbaceb2e568135a2d91a0fd6005e2df9d450b80822599a2eed4d7e2f937bea80c350bda0b";
    private static final String PAY_TO = "addr_test1wzs4e6wc95hkwezlccjw9mdvq0r0rsgx6zk34avptga3ftgn37w4g";
    /** termsDigest the TypeScript implementation computed for these terms. */
    private static final String EXPECTED_DIGEST = "d8275e410352d9277286f660d41536e2efbee5744232e272e606887f8aeb19b7";

    private static Map<String, Object> extra() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("version", "1");
        t.put("paymentType", "Web3CardanoV2");
        t.put("sellerAddress", "addr_test1qq4jrrcfzylccwgqu3su865es52jkf7yzrdu9cw3z84nycnn3zz9lvqj7vs95tej896xkekzkufhpuk64ja7pga2g8ksdf8km4");
        t.put("sellerNonce", "8d227855900ae6dcf47ee16a17461ab9a6b157a3d964b32ccd52dd038e42bd75");
        t.put("buyerNonce", "");
        t.put("inputHash", "b44bc52f6995a2009bbb36e662e2b3fc593d2c8154b8e7cf1c1387fdf69b8e15");
        t.put("payByTime", "1785931662349");
        t.put("submitResultTime", "1785932262349");
        t.put("unlockTime", "1785933462349");
        t.put("externalDisputeUnlockTime", "1785934662349");
        t.put("settlementPolicy", "l1");
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
        assertThat(termsDigest(requirements("5000000"))).isEqualTo(EXPECTED_DIGEST);
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
}
