package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-implementation compatibility for the Masumi digests.
 *
 * <p>The expected values were produced by the TypeScript reference implementation
 * from the same inputs. A divergence here means Java and TypeScript disagree
 * about what the seller signed, so a lock one considers authorized the other
 * would reject — or accept under terms nobody agreed to.
 */
class MasumiDigestsTest {

    private static final String SELLER =
            "addr_test1qq4jrrcfzylccwgqu3su865es52jkf7yzrdu9cw3z84nycnn3zz9lvqj7vs95tej896xkekzkufhpuk64ja7pga2g8ksdf8km4";
    private static final String ESCROW =
            "addr_test1wzs4e6wc95hkwezlccjw9mdvq0r0rsgx6zk34avptga3ftgn37w4g";

    /** Produced by the TypeScript `computeTermsDigest` for the terms below. */
    private static final String EXPECTED_TERMS_DIGEST =
            "c4e3f1479c9b1db9bda993f86e8cceafe96b9f2390574a28eef98bd5318b0830";
    /** Produced by the TypeScript `commitmentPartDigest` for the part below. */
    private static final String EXPECTED_PART_DIGEST =
            "8c19f7b2f612c6435d48dbd1d0760cce0686c6ff83bcc299de36ec773a1280f4";

    private static Map<String, Object> terms() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("version", "1");
        t.put("paymentType", "Web3CardanoV2");
        t.put("sellerAddress", SELLER);
        t.put("sellerNonce", "ab".repeat(32));
        t.put("buyerNonce", "");
        t.put("inputHash", "cd".repeat(32));
        t.put("payByTime", "1785827549507");
        t.put("submitResultTime", "1785828149507");
        t.put("unlockTime", "1785829349507");
        t.put("externalDisputeUnlockTime", "1785830549507");
        t.put("settlementPolicy", "l1");
        return t;
    }

    @Test
    void termsDigestMatchesTheTypeScriptImplementation() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "masumi");
        extra.put("terms", terms());
        PaymentRequirements requirements = new PaymentRequirements(
                "exact", "cardano:preprod", "lovelace", "5000000", ESCROW, 600, extra);

        Map<String, Object> signed = MasumiDigests.buildSignedTerms(extra, requirements);
        assertThat(MasumiDigests.computeTermsDigest(signed)).isEqualTo(EXPECTED_TERMS_DIGEST);
    }

    @Test
    void commitmentPartDigestMatchesTheTypeScriptImplementation() {
        Map<String, Object> content = new LinkedHashMap<>();
        // Deliberately not in sorted order: JCS must reorder, not preserve.
        content.put("endpoint", "/x");
        content.put("days", 3);
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("name", "parameters");
        part.put("canonicalization", "jcs");
        part.put("mediaType", "application/json");
        part.put("content", content);

        assertThat(MasumiDigests.commitmentPartDigest(part)).isEqualTo(EXPECTED_PART_DIGEST);
    }

    @Test
    void signedTermsProjectTheRequirementsFields() {
        // Projecting price, asset, network and escrow into the signature is what
        // stops a server re-quoting the same seller-signed terms elsewhere.
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "masumi");
        extra.put("terms", terms());
        PaymentRequirements base = new PaymentRequirements(
                "exact", "cardano:preprod", "lovelace", "5000000", ESCROW, 600, extra);
        PaymentRequirements dearer = new PaymentRequirements(
                "exact", "cardano:preprod", "lovelace", "6000000", ESCROW, 600, extra);

        assertThat(MasumiDigests.computeTermsDigest(MasumiDigests.buildSignedTerms(extra, base)))
                .isNotEqualTo(MasumiDigests.computeTermsDigest(MasumiDigests.buildSignedTerms(extra, dearer)));
    }

    @Test
    void jcsSortsKeysAndIsIndependentOfInsertionOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 1);
        a.put("a", List.of(1, 2));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", List.of(1, 2));
        b.put("b", 1);
        assertThat(MasumiDigests.jcs(a)).isEqualTo(MasumiDigests.jcs(b)).isEqualTo("{\"a\":[1,2],\"b\":1}");
    }
}
