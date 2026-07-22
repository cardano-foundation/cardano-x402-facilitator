package org.cardanofoundation.x402.facilitator.service.settlement;

import lombok.RequiredArgsConstructor;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;

import java.util.Map;

/**
 * Fast health gate for {@code /settle}. Settlement submits a transaction and
 * then confirms its on-chain inclusion; if the chain backend is unreachable
 * the facilitator is blind and MUST NOT accept the settlement — the
 * controller turns a closed gate into HTTP 503 (retryable) rather than a 200
 * with an ambiguous result. Verification stays in-band (returns
 * {@code chain_lookup_failed}) because it makes no state change.
 */
@RequiredArgsConstructor
public class SettlementGate {

    private final Map<String, FacilitatorChainService> chainsByNetwork;

    /**
     * True when the backend for {@code network} is healthy (or the network is not
     * one this facilitator serves — the registry handles that with its own error).
     */
    public boolean isHealthy(String network) {
        FacilitatorChainService chain = chainsByNetwork.get(CardanoNetworks.normalize(network));
        if (chain == null) return true;
        try {
            return chain.health().healthy();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
