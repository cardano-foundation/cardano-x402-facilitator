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
}
