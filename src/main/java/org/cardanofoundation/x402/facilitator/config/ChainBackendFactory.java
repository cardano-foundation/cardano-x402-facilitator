package org.cardanofoundation.x402.facilitator.config;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.chain.NetworkClock;
import org.cardanofoundation.x402.facilitator.chain.ProtocolParamsProvider;
import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.chain.blockfrost.BlockfrostChainService;
import org.cardanofoundation.x402.facilitator.chain.blockfrost.BlockfrostProtocolParamsProvider;

/**
 * Builds one chain-service graph per configured network entry (spec 4.2):
 * blockfrost directly, yaci-store via the profile-provided
 * {@link YaciStoreBackendProvider} (null outside the {@code yaci-store} profile,
 * in which case requesting yaci-store mode fails fast with guidance).
 */
public class ChainBackendFactory {

    private final YaciStoreBackendProvider yaciStoreBackendProvider;

    public ChainBackendFactory(YaciStoreBackendProvider yaciStoreBackendProvider) {
        this.yaciStoreBackendProvider = yaciStoreBackendProvider;
    }

    public record ChainBackend(FacilitatorChainService chainService,
                               ProtocolParamsProvider paramsProvider,
                               NetworkClock networkClock) {
    }

    public ChainBackend build(X402Properties.NetworkEntry entry, X402Properties props) {
        NetworkClock clock = ShelleyNetworkClock.forNetwork(entry.id(), entry.slotConfig());
        switch (entry.chain().mode()) {
            case "blockfrost" -> {
                X402Properties.Blockfrost bf = entry.chain().blockfrost();
                String baseUrl = bf.baseUrl().endsWith("/") ? bf.baseUrl() : bf.baseUrl() + "/";
                BFBackendService backend = new BFBackendService(baseUrl, bf.projectId());
                return new ChainBackend(
                        new BlockfrostChainService(backend, props.settle().pollIntervalOrDefault()),
                        new BlockfrostProtocolParamsProvider(backend),
                        clock);
            }
            case "yaci-store" -> {
                if (yaciStoreBackendProvider == null) {
                    throw new IllegalStateException("yaci-store chain mode requires the 'yaci-store' "
                            + "Spring profile (run with --spring.profiles.active=yaci-store and a "
                            + "reachable cardano-node + Postgres). See deploy/README.md.");
                }
                return yaciStoreBackendProvider.build(entry, props, clock);
            }
            default -> throw new IllegalStateException("Unknown chain mode: " + entry.chain().mode());
        }
    }
}
