package org.cardanofoundation.x402.facilitator.config;

import lombok.RequiredArgsConstructor;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Fail-fast configuration validation: every configured network id must normalize
 * to a supported Cardano network and declare a usable chain backend — a
 * {@code blockfrost} base-url (hosted Blockfrost, or a standalone yaci-store's
 * Blockfrost-compatible endpoint).
 */
@Component
@RequiredArgsConstructor
public class StartupValidation implements InitializingBean {

    private final X402Properties props;

    @Override
    public void afterPropertiesSet() {
        if (props.networks() == null || props.networks().isEmpty()) {
            throw new IllegalStateException("x402.networks must contain at least one network entry");
        }
        for (X402Properties.NetworkEntry entry : props.networks()) {
            if (!CardanoNetworks.isSupported(entry.id())) {
                throw new IllegalStateException("Unsupported network id: " + entry.id());
            }
            if (entry.chain() == null || entry.chain().blockfrost() == null
                    || entry.chain().blockfrost().baseUrl() == null) {
                throw new IllegalStateException(
                        "network " + entry.id() + " requires chain.blockfrost.base-url");
            }
        }
    }
}
