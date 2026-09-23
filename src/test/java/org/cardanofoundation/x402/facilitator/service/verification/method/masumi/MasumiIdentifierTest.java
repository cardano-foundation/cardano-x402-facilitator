package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decodes the spec's published compatibility-identifier vector.
 *
 * <p>The vector is fixed in scheme_exact_cardano.md and asserted identically by
 * the TypeScript implementation, so this proves the LZString port is faithful —
 * a bit-level divergence would silently mis-parse the escrow address.
 */
class MasumiIdentifierTest {

    /** blockchainIdentifier from the spec's identifier test vector. */
    private static final String SPEC_IDENTIFIER =
            "230d7c6574f41d1c0acc96ade8eae04360019f607004d8809c07d005c053019cae007700bce8058680d89818c04e44002c035931a2c00daf5e00ac9bf00b6c401b80473c6535d00e6003cb8b110199db615001ca8eecc6019b58076c603b13763a80";

    private static final String SPEC_TEXT =
            "1111111111111111111111111111111111111111111111111111111111111111..55555555555555555555555555555555.a10101."
                    + "addr_test1wzs4e6wc95hkwezlccjw9mdvq0r0rsgx6zk34avptga3ftgn37w4g";

    @Test
    void decodesTheSpecVector() {
        MasumiIdentifier.IdentifierParts parts = MasumiIdentifier.decode(SPEC_IDENTIFIER);
        assertThat(parts).isNotNull();
        assertThat(parts.sellerNonce()).isEqualTo("11".repeat(32));
        assertThat(parts.agentIdentifier()).isEmpty();
        assertThat(parts.buyerNonce()).isEmpty();
        assertThat(parts.referenceSignature()).isEqualTo("55".repeat(16));
        assertThat(parts.referenceKey()).isEqualTo("a10101");
        assertThat(parts.contractAddress())
                .isEqualTo("addr_test1wzs4e6wc95hkwezlccjw9mdvq0r0rsgx6zk34avptga3ftgn37w4g");
    }

    @Test
    void roundTripsThroughTheIdentifierText() {
        MasumiIdentifier.IdentifierParts parts = MasumiIdentifier.decode(SPEC_IDENTIFIER);
        assertThat(MasumiIdentifier.buildIdentifierText(parts)).isEqualTo(SPEC_TEXT);
    }

    @Test
    void rejectsMalformedIdentifiers() {
        assertThat(MasumiIdentifier.decode("")).isNull();
        assertThat(MasumiIdentifier.decode("abc")).isNull();          // odd length
        assertThat(MasumiIdentifier.decode("ZZZZ")).isNull();         // not hex
        assertThat(MasumiIdentifier.decode("AABB")).isNull();         // uppercase
        assertThat(MasumiIdentifier.decode("deadbeef")).isNull();     // not five segments
    }
    @Test void boundsExpansionBeforeAllocatingAnOversizedIdentifier() {
        // Produced by lz-string compressToUint8Array: five segments, 33,000 buyer-nonce characters.
        String bomb = "230d7c6574f41d010c9c96ade8e6bd9eeff830a38934b3c8b2aba9b6bbe871a79975b7d8f3afb9f7bff818287091a2c788992a7499b2e7c858a97295aad7a8d9ab769dbaf7e83868f193a6cf98b96af59bb6efd878e9f397aedfb8f9ebf79fbefff80c0a0e090d0b0f088c8a8e898d8b8f884c4a4e494d4b4f48cccacec9cdcbcfc82c2a2e292d2b2f28acaaaea9adabafa86c6a6e696d6b6f68eceaeee9edebefe81c1a1e191d1b1f189c9a9e999d9b9f985c5a5e595d5b5f58dcdaded9dddbdfd83c3a3e393d3b3f38bcbabeb9bdbbbfb87c7a7e797d7b7f78fcfafef9fdfbfff8060281c090682c1e088642a1d0986c2e1f084478e00056545a3d118cc563d1886000019f1c0440004d890027003e8005c00a600672a70000660800358d280000";
        assertThat(MasumiIdentifier.decode(bomb)).isNull();
        assertThat(MasumiIdentifier.decode("00".repeat(8193))).isNull();
    }

}
