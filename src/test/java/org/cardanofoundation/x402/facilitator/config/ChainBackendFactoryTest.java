package org.cardanofoundation.x402.facilitator.config;

import org.cardanofoundation.x402.facilitator.config.ChainBackendFactory.ChainBackend;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainBackendFactoryTest {

    /**
     * Regression: a trimmed application.yml omits the whole {@code x402.settle}
     * section, so {@code props.settle()} is null in production. build() must fall
     * back to the code-default poll interval, not NPE. The test config still ships
     * a settle section, so only this unit test exercises the absent-section path.
     */
    @Test void buildToleratesAbsentOptionalConfig() {
        X402Properties.NetworkEntry entry = new X402Properties.NetworkEntry(
                "cardano:preprod", null,
                new X402Properties.ChainConfig(
                        new X402Properties.Blockfrost("https://cardano-preprod.blockfrost.io/api/v0", "")),
                null);
        // verification / settle / duplicateCache / http / masumi / security all absent.
        X402Properties props = new X402Properties(List.of(entry), null, null, null, null, null, null);

        ChainBackend backend = new ChainBackendFactory().build(entry, props);

        assertThat(backend).isNotNull();
        assertThat(backend.chainService()).isNotNull();
        assertThat(backend.paramsProvider()).isNotNull();
        assertThat(backend.networkClock()).isNotNull();
    }
}
