package org.cardanofoundation.x402.facilitator.config;

import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.model.chain.BackendHealth;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChainReadinessTest {
    @Test void requiredFailureMakesBothViewsUnavailableWhileOptionalFailureDoesNot() {
        var required = mock(FacilitatorChainService.class);
        var optional = mock(FacilitatorChainService.class);
        when(required.health()).thenReturn(BackendHealth.ok());
        when(optional.health()).thenReturn(BackendHealth.down("indexer starting"));
        var props = new X402Properties(List.of(
                new X402Properties.NetworkEntry("cardano:preprod", true, null, null),
                new X402Properties.NetworkEntry("cardano:preview", false, null, null)),
                null, null, null, null, null);
        var readiness = new ChainReadiness(props, Map.of(
                "cardano:preprod", new ChainBackendFactory.ChainBackend(required, null, null),
                "cardano:preview", new ChainBackendFactory.ChainBackend(optional, null, null)));
        assertThat(readiness.snapshot().ready()).isTrue();
        assertThat(readiness.health().getStatus().getCode()).isEqualTo("UP");
        when(required.health()).thenReturn(BackendHealth.down("tip stale"));
        assertThat(readiness.snapshot().ready()).isFalse();
        assertThat(readiness.health().getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test void missingRequiredBackendIsUnavailable() {
        var props = new X402Properties(List.of(
                new X402Properties.NetworkEntry("cardano:preprod", true, null, null)),
                null, null, null, null, null);
        assertThat(new ChainReadiness(props, Map.of()).snapshot().ready()).isFalse();
    }
}
