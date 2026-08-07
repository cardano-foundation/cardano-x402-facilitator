package org.cardanofoundation.x402.facilitator.testutil;

import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiCose;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The test seller must produce signatures the production verifier accepts. */
class MasumiTestSellerSelfTest {
    @Test void producesAnAuthorizationTheVerifierAccepts() {
        MasumiTestSeller seller = new MasumiTestSeller("77");
        String digest = "ab".repeat(32);
        assertThat(MasumiCose.verifySellerTermsSignature(
                seller.referenceKeyHex(), seller.signTermsHex(digest), digest, seller.sellerAddress))
                .isTrue();
        // A signature over other terms must not verify against this digest.
        assertThat(MasumiCose.verifySellerTermsSignature(
                seller.referenceKeyHex(), seller.signTermsHex("cd".repeat(32)), digest, seller.sellerAddress))
                .isFalse();
    }
}
