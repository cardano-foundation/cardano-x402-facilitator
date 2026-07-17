package org.cardanofoundation.x402.facilitator.service.settlement;

import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.testutil.FakeChainService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementGateTest {

    @Test void healthyBackendOpensGate() {
        SettlementGate gate = new SettlementGate(Map.of("cardano:preprod", new FakeChainService()));
        assertThat(gate.isHealthy("cardano:preprod")).isTrue();
    }

    @Test void unhealthyBackendClosesGate() {
        FakeChainService down = new FakeChainService();
        down.throwOnLookup = true; // FakeChainService.health() reports down in this state
        SettlementGate gate = new SettlementGate(Map.of("cardano:preprod", down));
        assertThat(gate.isHealthy("cardano:preprod")).isFalse();
    }

    @Test void unknownNetworkIsLeftToTheRegistry() {
        SettlementGate gate = new SettlementGate(Map.of("cardano:preprod", new FakeChainService()));
        assertThat(gate.isHealthy("cardano:mainnet")).isTrue();
    }

    @Test void aliasNetworkNormalizesToConfiguredBackend() {
        FakeChainService down = new FakeChainService();
        down.throwOnLookup = true;
        SettlementGate gate = new SettlementGate(Map.of("cardano:preprod", down));
        // cip34:0-1 is the CIP-34 alias for preprod; the gate must resolve it.
        assertThat(gate.isHealthy("cip34:0-1")).isFalse();
    }

    @Test void healthProbeExceptionClosesGate() {
        FacilitatorChainService throwing = new FakeChainService() {
            @Override
            public org.cardanofoundation.x402.facilitator.model.chain.BackendHealth health() {
                throw new RuntimeException("probe blew up");
            }
        };
        SettlementGate gate = new SettlementGate(Map.of("cardano:preprod", throwing));
        assertThat(gate.isHealthy("cardano:preprod")).isFalse();
    }
}
