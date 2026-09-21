package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Golden digests produced by the pinned TypeScript package, never Java code under test. */
class MasumiDigestsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode fixtures() throws Exception {
        try (var input = MasumiDigestsTest.class.getResourceAsStream("/upstream/payments.json")) {
            return JSON.readTree(input);
        }
    }

    @Test void termsAndCommitmentDigestsMatchPinnedTypeScript() throws Exception {
        for (JsonNode payment : fixtures().get("payments")) {
            if (!payment.get("name").asText().startsWith("masumi-")) continue;
            var req = JSON.treeToValue(payment.get("requirements"), PaymentRequirements.class);
            assertThat(MasumiDigests.computeTermsDigest(MasumiDigests.buildSignedTerms(req.extra(), req)))
                    .isEqualTo(payment.get("termsDigest").asText());
            var commitment = MasumiSchemaTest.object(req.extra(), "inputCommitment");
            assertThat(MasumiDigests.computeInputHash(commitment)).isEqualTo(payment.get("inputHash").asText());
        }
    }

    @Test void numbersUnicodeNullAndKeyOrderMatchPinnedTypeScript() throws Exception {
        for (JsonNode vector : fixtures().get("jcs")) {
            Object content = JSON.treeToValue(vector.get("content"), Object.class);
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("canonicalization", "jcs"); part.put("content", content);
            assertThat(MasumiDigests.jcs(content)).isEqualTo(vector.get("canonical").asText());
            assertThat(MasumiDigests.commitmentPartDigest(part)).isEqualTo(vector.get("digest").asText());
        }
    }

    @Test void signedTermsProjectTheRequirementsFields() throws Exception {
        var payment = fixtures().get("payments").get(6);
        var base = JSON.treeToValue(payment.get("requirements"), PaymentRequirements.class);
        var dearer = new PaymentRequirements(base.scheme(), base.network(), base.asset(), "9000000",
                base.payTo(), base.maxTimeoutSeconds(), base.extra());
        assertThat(MasumiDigests.computeTermsDigest(MasumiDigests.buildSignedTerms(base.extra(), base)))
                .isNotEqualTo(MasumiDigests.computeTermsDigest(MasumiDigests.buildSignedTerms(base.extra(), dearer)));
    }

    @Test void signedAgentIdentifierOmittedNullAndEmptyRemainDistinct() {
        var extra = MasumiTransferVerifierTest.defaultExtra();
        var terms = MasumiSchemaTest.object(extra, "terms");
        terms.remove("agentIdentifier");
        String omitted = MasumiDigests.computeTermsDigest(MasumiDigests.buildSignedTerms(extra, MasumiTransferVerifierTest.requirements(extra)));
        terms.put("agentIdentifier", null);
        String explicitNull = MasumiDigests.computeTermsDigest(MasumiDigests.buildSignedTerms(extra, MasumiTransferVerifierTest.requirements(extra)));
        terms.put("agentIdentifier", "");
        String empty = MasumiDigests.computeTermsDigest(MasumiDigests.buildSignedTerms(extra, MasumiTransferVerifierTest.requirements(extra)));
        assertThat(List.of(omitted, explicitNull, empty)).doesNotHaveDuplicates();
    }

    @Test void confirmationPolicyIsOutsideSellerSignature() {
        var extra = MasumiTransferVerifierTest.defaultExtra();
        String before = MasumiDigests.computeTermsDigest(MasumiDigests.buildSignedTerms(extra, MasumiTransferVerifierTest.requirements(extra)));
        extra.put("confirmationPolicy", Map.of("l1Confirmations", 20));
        assertThat(MasumiDigests.computeTermsDigest(MasumiDigests.buildSignedTerms(extra, MasumiTransferVerifierTest.requirements(extra))))
                .isEqualTo(before);
    }

    @Test void jcsSortsKeysAndIsIndependentOfInsertionOrder() {
        Map<String, Object> a = new LinkedHashMap<>(); a.put("b", 1); a.put("a", List.of(1, 2));
        Map<String, Object> b = new LinkedHashMap<>(); b.put("a", List.of(1, 2)); b.put("b", 1);
        assertThat(MasumiDigests.jcs(a)).isEqualTo(MasumiDigests.jcs(b)).isEqualTo("{\"a\":[1,2],\"b\":1}");
    }
}
