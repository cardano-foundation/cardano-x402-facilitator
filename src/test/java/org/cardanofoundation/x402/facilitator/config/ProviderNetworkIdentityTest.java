package org.cardanofoundation.x402.facilitator.config;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.model.Block;
import com.bloxbean.cardano.client.backend.model.Genesis;
import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProviderNetworkIdentityTest {
    @ParameterizedTest
    @CsvSource({"cardano:mainnet,764824073", "cardano:preprod,1", "cardano:preview,2", "cip34:0-1,1"})
    void matchingProviderIsHealthyAndIdentityIsCached(String network, int magic) throws Exception {
        try (var construction = mockConstruction(BFBackendService.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS),
                (backend, context) -> {
                    var genesis = new Genesis(); genesis.setNetworkMagic(magic);
                    when(backend.getNetworkInfoService().getNetworkInfo()).thenReturn(Result.success("").withValue(genesis));
                    var tip = new Block();
                    tip.setSlot(ShelleyNetworkClock.forNetwork(network, null).expectedSlotAt(java.time.Instant.now()));
                    tip.setHeight(1);
                    when(backend.getBlockService().getLatestBlock()).thenReturn(Result.success("").withValue(tip));
                })) {
            var chain = build(network).chainService();
            assertThat(chain.health().healthy()).isTrue();
            assertThat(chain.health().healthy()).isTrue();
            verify(construction.constructed().getFirst().getNetworkInfoService()).getNetworkInfo();
        }
    }

    @Test void wrongNetworkIsUnhealthyAndCannotReadOrSubmitPayments() throws Exception {
        try (var construction = mockConstruction(BFBackendService.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS),
                (backend, context) -> {
                    var genesis = new Genesis(); genesis.setNetworkMagic(764824073);
                    when(backend.getNetworkInfoService().getNetworkInfo()).thenReturn(Result.success("").withValue(genesis));
                    when(backend.getBlockService().getLatestBlock()).thenReturn(Result.success("").withValue(new Block()));
                })) {
            var chain = build("cardano:preprod").chainService();
            assertThat(chain.health().healthy()).isFalse();
            assertThatThrownBy(() -> chain.getUtxoState(TestTx.NONCE_TX_HASH, 0)).isInstanceOf(ChainLookupException.class);
            assertThatThrownBy(() -> chain.checkInclusion(TestTx.NONCE_TX_HASH)).isInstanceOf(ChainLookupException.class);
            assertThat(chain.submitTransaction(Base64.getDecoder().decode(TestTx.buildBase64(TestTx.Spec.defaults()))))
                    .isInstanceOf(SubmissionResult.NotSubmitted.class);
            verify(construction.constructed().getFirst().getTransactionService(), never()).submitTransaction(any(byte[].class));
        }
    }

    @Test void unavailableIdentityFailsClosed() throws Exception {
        try (var construction = mockConstruction(BFBackendService.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS),
                (backend, context) -> {
                    when(backend.getNetworkInfoService().getNetworkInfo()).thenReturn(Result.error("offline").code(503));
                    when(backend.getBlockService().getLatestBlock()).thenReturn(Result.success("").withValue(new Block()));
                })) {
            assertThat(build("cardano:preprod").chainService().health().healthy()).isFalse();
        }
    }

    private ChainBackendFactory.ChainBackend build(String network) {
        var entry = new X402Properties.NetworkEntry(network, true,
                new X402Properties.ChainConfig(new X402Properties.Blockfrost("https://example.invalid/", "")), null);
        return new ChainBackendFactory().build(entry, new X402Properties(List.of(entry), null, null, null, null, null));
    }
}
