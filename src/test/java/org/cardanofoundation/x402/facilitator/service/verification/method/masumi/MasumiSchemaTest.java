package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MasumiSchemaTest {
    static Map<String, Object> valid() {
        Map<String, Object> terms = new LinkedHashMap<>(Map.of(
                "version", "1", "paymentType", "Web3CardanoV2", "sellerAddress", TestTx.SELLER_ADDRESS,
                "sellerNonce", "ab".repeat(32), "buyerNonce", "", "inputHash", "cd".repeat(32),
                "payByTime", "2000000000000", "submitResultTime", "2000000600000",
                "unlockTime", "2000001800000", "externalDisputeUnlockTime", "2000003000000"));
        return new LinkedHashMap<>(Map.of("assetTransferMethod", "masumi", "terms", terms,
                "referenceKey", "00", "referenceSignature", "00", "blockchainIdentifier", "00",
                "inputCommitment", new LinkedHashMap<>(Map.of("version", "1", "algorithm", "sha256",
                        "digest", "cd".repeat(32), "parts", List.of(part())))));
    }

    static Map<String, Object> part() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "request"); p.put("canonicalization", "jcs"); p.put("digest", "ab".repeat(32));
        p.put("content", null);
        return p;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> parent, String field) {
        return (Map<String, Object>) parent.get(field);
    }

    @Test void acceptsValidCompleteShapeAndJcsNull() {
        assertThat(MasumiSchema.validate(valid())).isEmpty();
    }

    @Test void requiresEveryRequiredField() {
        for (String field : List.of("inputCommitment", "blockchainIdentifier", "referenceKey", "referenceSignature", "terms")) {
            var extra = valid(); extra.remove(field);
            assertThat(MasumiSchema.validate(extra)).as(field).isPresent();
        }
        for (String field : List.of("version", "paymentType", "sellerAddress", "sellerNonce", "buyerNonce", "inputHash",
                "payByTime", "submitResultTime", "unlockTime", "externalDisputeUnlockTime")) {
            var extra = valid(); object(extra, "terms").remove(field);
            assertThat(MasumiSchema.validate(extra)).as("terms.%s", field).isPresent();
        }
    }

    @Test void rejectsRemovedPolicyAndUnknownFields() {
        for (String field : List.of("submissionPolicy", "unknown")) {
            var extra = valid(); extra.put(field, "server");
            assertThat(MasumiSchema.validate(extra)).as(field).isPresent();
        }
        var extra = valid(); object(extra, "terms").put("settlementPolicy", "l1");
        assertThat(MasumiSchema.validate(extra)).isPresent();
    }

    @Test void rejectsWrongTypesAndNoncanonicalValues() {
        for (Object value : List.of("false", 0, true)) {
            var extra = valid(); extra.put("areFeesSponsored", value);
            assertThat(MasumiSchema.validate(extra)).as("fees %s", value).isPresent();
        }
        for (Object value : List.of("01", "0", "-1", "1.5", " 12", 12, "9".repeat(21))) {
            var extra = valid(); object(extra, "terms").put("payByTime", value);
            assertThat(MasumiSchema.validate(extra)).as("deadline %s", value).isPresent();
        }
        for (String field : List.of("referenceKey", "referenceSignature", "blockchainIdentifier")) {
            for (String value : List.of("", "AA", "abc", "00".repeat(16385))) {
                var extra = valid(); extra.put(field, value);
                assertThat(MasumiSchema.validate(extra)).as(field).isPresent();
            }
        }
        for (String field : List.of("confirmationPolicy", "deployment", "areFeesSponsored")) {
            var extra = valid(); extra.put(field, null);
            assertThat(MasumiSchema.validate(extra)).as("null %s", field).isPresent();
        }
    }

    @Test void rejectsMalformedConfirmationPolicy() {
        for (Object value : List.of("1", 1.5, new BigInteger("18446744073709551617"), 21, -2)) {
            var extra = valid(); extra.put("confirmationPolicy", Map.of("l1Confirmations", value));
            assertThat(MasumiSchema.validate(extra)).as("confirmation %s", value).isPresent();
        }
        var extra = valid(); extra.put("confirmationPolicy", Map.of("l1Confirmations", 1, "unknown", 0));
        assertThat(MasumiSchema.validate(extra)).isPresent();
    }

    @Test void rejectsDuplicateCommitmentNames() {
        var extra = valid(); object(extra, "inputCommitment").put("parts", List.of(part(), part()));
        assertThat(MasumiSchema.validate(extra)).isPresent();
    }

    @Test void boundsPartCountNamesAndContent() {
        var extra = valid(); object(extra, "inputCommitment").put("parts", java.util.Collections.nCopies(33, part()));
        assertThat(MasumiSchema.validate(extra)).isPresent();
        for (Object content : List.of("x".repeat(1024 * 1024 + 1), Double.NaN)) {
            extra = valid(); var part = part(); part.put("content", content);
            object(extra, "inputCommitment").put("parts", List.of(part));
            assertThat(MasumiSchema.validate(extra)).isPresent();
        }
        Object nested = 0;
        for (int i = 0; i < 65; i++) nested = List.of(nested);
        extra = valid(); var part = part(); part.put("content", nested);
        object(extra, "inputCommitment").put("parts", List.of(part));
        assertThat(MasumiSchema.validate(extra)).isPresent();
        List<Object> cycle = new ArrayList<>(); cycle.add(cycle); part.put("content", cycle);
        assertThat(MasumiSchema.validate(extra)).isPresent();
    }

    @Test void acceptsOnlyCanonicalUnpaddedRawBase64url() {
        for (Object content : List.of("YQ==", "YR", "+/8", 123)) {
            var extra = valid(); var part = part(); part.put("canonicalization", "raw"); part.put("content", content);
            object(extra, "inputCommitment").put("parts", List.of(part));
            assertThat(MasumiSchema.validate(extra)).as("raw %s", content).isPresent();
        }
        var extra = valid(); var part = part(); part.put("canonicalization", "raw"); part.put("content", "YQ");
        object(extra, "inputCommitment").put("parts", List.of(part));
        assertThat(MasumiSchema.validate(extra)).isEmpty();
    }

    @Test void rejectsMalformedDeploymentAndRegistryIdentifier() {
        for (Object deployment : List.of("bad", Map.of(), Map.of("requiredAdmins", "2", "adminVkeys", List.of("ab".repeat(28)), "cooldownPeriod", "0"))) {
            var extra = valid(); extra.put("deployment", deployment);
            assertThat(MasumiSchema.validate(extra)).isPresent();
        }
        for (Object value : List.of("AB", "a", "ab".repeat(61), 1)) {
            var extra = valid(); object(extra, "terms").put("agentIdentifier", value);
            assertThat(MasumiSchema.validate(extra)).isPresent();
        }
    }

    @Test void rejectsSellerReturnNullAndCommitmentHashDisagreement() {
        var extra = valid(); object(extra, "terms").put("sellerReturnAddress", null);
        assertThat(MasumiSchema.validate(extra)).isPresent();
        extra = valid(); object(extra, "terms").put("inputHash", "ee".repeat(32));
        assertThat(MasumiSchema.validate(extra)).isPresent();
    }
}
