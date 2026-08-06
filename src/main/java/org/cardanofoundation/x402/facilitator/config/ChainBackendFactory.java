package org.cardanofoundation.x402.facilitator.config;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.chain.NetworkClock;
import org.cardanofoundation.x402.facilitator.chain.ProtocolParamsProvider;
import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.chain.blockfrost.BlockfrostChainService;
import org.cardanofoundation.x402.facilitator.chain.blockfrost.BlockfrostProtocolParamsProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds one chain-service graph per configured network entry. The only
 * backend is the cardano-client-lib Blockfrost provider ({@code BFBackendService}).
 * Because yaci-store exposes a Blockfrost-compatible API, the same client serves a
 * standalone yaci-store just as well as hosted Blockfrost — set
 * {@code chain.blockfrost.base-url} to whichever endpoint you run. yaci-store is a
 * deployment choice, not a distinct code path.
 */
@Component
public class ChainBackendFactory {

    public record ChainBackend(FacilitatorChainService chainService,
                               ProtocolParamsProvider paramsProvider,
                               NetworkClock networkClock) {
    }

    public ChainBackend build(X402Properties.NetworkEntry entry, X402Properties props) {
        NetworkClock clock = ShelleyNetworkClock.forNetwork(entry.id(), entry.slotConfig());
        X402Properties.Blockfrost bf = entry.chain() == null ? null : entry.chain().blockfrost();
        if (bf == null || bf.baseUrl() == null) {
            throw new IllegalStateException(
                    "network " + entry.id() + " requires chain.blockfrost.base-url");
        }
        String baseUrl = bf.baseUrl().endsWith("/") ? bf.baseUrl() : bf.baseUrl() + "/";
        BFBackendService backend = new BFBackendService(baseUrl, bf.projectId() == null ? "" : bf.projectId());
        // x402.settle is optional (absent = code defaults), so props.settle() may be null.
        Duration pollInterval = props.settle() == null
                ? Duration.ofSeconds(3) : props.settle().pollIntervalOrDefault();
        return new ChainBackend(
                new BlockfrostChainService(backend, pollInterval),
                new BlockfrostProtocolParamsProvider(backend),
                clock);
    }
}
