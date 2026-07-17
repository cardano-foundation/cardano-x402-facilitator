package org.cardanofoundation.x402.facilitator.config;

import org.cardanofoundation.x402.facilitator.chain.NetworkClock;

/**
 * Builds a yaci-store-backed {@link ChainBackendFactory.ChainBackend} for a network.
 * A bean of this type exists ONLY under the {@code yaci-store} Spring profile
 * (provided by {@code YaciStoreConfiguration}); the default (blockfrost) context
 * has none, and {@link ChainBackendFactory} then fails fast if yaci-store mode is
 * requested without the profile.
 */
public interface YaciStoreBackendProvider {

    ChainBackendFactory.ChainBackend build(X402Properties.NetworkEntry entry,
                                           X402Properties props, NetworkClock clock);
}
