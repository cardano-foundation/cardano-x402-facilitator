package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MasumiUnicodeTest {
    static Stream<String> malformedUnicode() {
        String high = String.valueOf((char) 0xd800);
        String low = String.valueOf((char) 0xdc00);
        return Stream.of(high, low, high + "a", "a" + low, high + high, low + low, low + high);
    }

    @ParameterizedTest(name = "malformed value {index}")
    @MethodSource("malformedUnicode")
    void canonicalizerRejectsUnpairedSurrogatesInValues(String invalid) {
        assertThatThrownBy(() -> MasumiDigests.jcs(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MasumiDigests.jcs(Map.of("nested", List.of(invalid))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "malformed key {index}")
    @MethodSource("malformedUnicode")
    void canonicalizerRejectsUnpairedSurrogatesInObjectKeys(String invalid) {
        assertThatThrownBy(() -> MasumiDigests.jcs(Map.of(invalid, "value")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "commitment content {index}")
    @MethodSource("malformedUnicode")
    void commitmentDigestRejectsMalformedJcsContent(String invalid) {
        assertThatThrownBy(() -> MasumiDigests.commitmentPartDigest(Map.of("canonicalization", "jcs", "content", invalid)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MasumiDigests.commitmentPartDigest(Map.of("canonicalization", "jcs", "content", Map.of(invalid, true))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "commitment manifest {index}")
    @MethodSource("malformedUnicode")
    void manifestDigestRejectsMalformedNamesAndMediaTypes(String invalid) {
        for (String field : List.of("name", "mediaType")) {
            var extra = MasumiSchemaTest.valid();
            var part = MasumiSchemaTest.part(); part.put(field, invalid);
            var commitment = MasumiSchemaTest.object(extra, "inputCommitment");
            commitment.put("parts", List.of(part));
            assertThatThrownBy(() -> MasumiDigests.computeInputHash(commitment))
                    .as(field).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest(name = "signed terms {index}")
    @MethodSource("malformedUnicode")
    void signedTermsDigestRejectsMalformedKeysAndEveryStringValue(String invalid) {
        var extra = MasumiTransferVerifierTest.defaultExtra();
        var signed = MasumiDigests.buildSignedTerms(extra, MasumiTransferVerifierTest.requirements(extra));
        for (var entry : signed.entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            var mutated = new LinkedHashMap<>(signed); mutated.put(entry.getKey(), invalid);
            assertThatThrownBy(() -> MasumiDigests.computeTermsDigest(mutated))
                    .as(entry.getKey()).isInstanceOf(IllegalArgumentException.class);
        }
        var mutated = new LinkedHashMap<>(signed); mutated.put(invalid, "value");
        assertThatThrownBy(() -> MasumiDigests.computeTermsDigest(mutated)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void presentMalformedContentIsACommitmentFailureWhileAbsentContentIsAccepted() {
        var verifier = new MasumiTransferVerifierTest(); verifier.setUp();
        var extra = MasumiTransferVerifierTest.defaultExtra();
        @SuppressWarnings("unchecked") var parts = (List<Map<String, Object>>) MasumiSchemaTest.object(extra, "inputCommitment").get("parts");
        var part = parts.getFirst();
        part.remove("content");
        assertThat(verifier.verify(org.cardanofoundation.x402.facilitator.testutil.TestTx.MasumiSpec.defaults(), extra).isValid()).isTrue();
        part.put("content", String.valueOf((char) 0xd800));
        assertThat(verifier.verify(org.cardanofoundation.x402.facilitator.testutil.TestTx.MasumiSpec.defaults(), extra).invalidReason())
                .isEqualTo(org.cardanofoundation.x402.facilitator.model.ErrorCodes.MASUMI_COMMITMENT);
    }

    @Test void malformedManifestStringsUseUpstreamGenericVerificationFailure() {
        var verifier = new MasumiTransferVerifierTest(); verifier.setUp();
        for (String field : List.of("name", "mediaType")) {
            var extra = MasumiTransferVerifierTest.defaultExtra();
            @SuppressWarnings("unchecked") var parts = (List<Map<String, Object>>) MasumiSchemaTest.object(extra, "inputCommitment").get("parts");
            var part = parts.getFirst(); part.remove("content"); part.put(field, String.valueOf((char) 0xdc00));
            assertThat(verifier.verify(org.cardanofoundation.x402.facilitator.testutil.TestTx.MasumiSpec.defaults(), extra).invalidReason())
                    .as(field).isEqualTo(org.cardanofoundation.x402.facilitator.model.ErrorCodes.VERIFICATION_ERROR);
        }
    }

    @Test void validSurrogatePairsAndNullRemainCanonicalizable() {
        String emoji = new String(Character.toChars(0x1f600));
        var content = new LinkedHashMap<String, Object>(); content.put(emoji, "prefix" + emoji + "suffix"); content.put("null", null);
        assertThat(MasumiDigests.jcs(content)).isEqualTo("{\"null\":null,\"" + emoji + "\":\"prefix" + emoji + "suffix\"}");
        assertThat(MasumiDigests.jcs(null)).isEqualTo("null");
        var extra = MasumiSchemaTest.valid(); var part = MasumiSchemaTest.part();
        part.put("name", emoji); part.put("mediaType", "type/" + emoji); part.put("content", content);
        MasumiSchemaTest.object(extra, "inputCommitment").put("parts", List.of(part));
        assertThat(MasumiSchema.validate(extra)).isEmpty();
    }
}
