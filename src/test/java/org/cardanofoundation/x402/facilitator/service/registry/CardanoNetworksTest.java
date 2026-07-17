package org.cardanofoundation.x402.facilitator.service.registry;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CardanoNetworksTest {
    @Test void normalizesCip34Aliases() {
        assertThat(CardanoNetworks.normalize("cip34:0-1")).isEqualTo("cardano:preprod");
        assertThat(CardanoNetworks.normalize("cip34:1-764824073")).isEqualTo("cardano:mainnet");
        assertThat(CardanoNetworks.normalize("cip34:0-2")).isEqualTo("cardano:preview");
        assertThat(CardanoNetworks.normalize("cardano:preprod")).isEqualTo("cardano:preprod");
        assertThat(CardanoNetworks.normalize("base-sepolia")).isEqualTo("base-sepolia");
    }
    @Test void normalizeIsExactMatchNoCaseOrWhitespaceFolding() {
        // Headline correctness property: a normalize that lowercases/trims is a DEFECT.
        assertThat(CardanoNetworks.normalize("CARDANO:PREPROD")).isEqualTo("CARDANO:PREPROD");
        assertThat(CardanoNetworks.isSupported("CARDANO:PREPROD")).isFalse();
        assertThat(CardanoNetworks.normalize(" cardano:preprod ")).isEqualTo(" cardano:preprod ");
        assertThat(CardanoNetworks.isSupported(" cardano:preprod ")).isFalse();
    }
    @Test void isSupportedNullReturnsFalseWithoutThrowing() {
        assertThat(CardanoNetworks.isSupported(null)).isFalse();
    }
    @Test void networkIds() {
        assertThat(CardanoNetworks.networkId("cardano:preprod")).isZero();
        assertThat(CardanoNetworks.networkId("cardano:preview")).isZero();
        assertThat(CardanoNetworks.networkId("cardano:mainnet")).isEqualTo(1);
    }
}
