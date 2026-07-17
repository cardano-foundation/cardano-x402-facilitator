package org.cardanofoundation.x402.facilitator.config;

import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Fail-fast configuration validation (spec sections 9.3 and 10):
 * - every configured network id must normalize to a supported Cardano network;
 * - at most one network entry may run the embedded yaci-store pipeline;
 * - accept-mempool=true is rejected while any network is yaci-store light
 *   (an N2N "accepted" carries no node verdict and must never convert into
 *   an immediate mempool success).
 */
@Component
public class StartupValidation implements InitializingBean {

    private final X402Properties props;

    public StartupValidation(X402Properties props) {
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() {
        if (props.networks() == null || props.networks().isEmpty()) {
            throw new IllegalStateException("x402.networks must contain at least one network entry");
        }
        long yaciCount = 0;
        for (X402Properties.NetworkEntry entry : props.networks()) {
            if (!CardanoNetworks.isSupported(entry.id())) {
                throw new IllegalStateException("Unsupported network id: " + entry.id());
            }
            if (entry.chain() == null || entry.chain().mode() == null) {
                throw new IllegalStateException("Missing chain.mode for network " + entry.id());
            }
            switch (entry.chain().mode()) {
                case "blockfrost" -> {
                    if (entry.chain().blockfrost() == null || entry.chain().blockfrost().baseUrl() == null) {
                        throw new IllegalStateException("blockfrost mode requires blockfrost.base-url for " + entry.id());
                    }
                }
                case "yaci-store" -> yaciCount++;
                default -> throw new IllegalStateException(
                        "Unknown chain.mode '" + entry.chain().mode() + "' for " + entry.id());
            }
        }
        if (yaciCount > 1) {
            throw new IllegalStateException(
                    "At most one network may use the embedded yaci-store pipeline (found " + yaciCount + ")");
        }
        boolean anyYaciLight = props.networks().stream()
                .anyMatch(n -> "yaci-store".equals(n.chain().mode()) && isLightVariant());
        if (anyYaciLight && props.settle() != null && props.settle().acceptMempoolOrDefault()) {
            throw new IllegalStateException(
                    "x402.settle.accept-mempool=true is not allowed with a yaci-store light network: "
                            + "N2N submission carries no node verdict (spec section 9.3)");
        }
    }

    private boolean isLightVariant() {
        // Full variant is selected by the presence of an N2C endpoint (store.cardano.
        // n2c-node-socket-path or n2c-host). Those live outside x402.* — resolved via
        // environment lookup when the yaci backend lands (P6). Until then a yaci-store
        // entry is treated as light, the conservative direction for this guard.
        return System.getProperty("store.cardano.n2c-node-socket-path",
                System.getenv().getOrDefault("STORE_CARDANO_N2C_NODE_SOCKET_PATH", "")).isEmpty();
    }
}
