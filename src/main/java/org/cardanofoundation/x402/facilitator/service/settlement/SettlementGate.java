package org.cardanofoundation.x402.facilitator.service.settlement;

import org.cardanofoundation.x402.facilitator.repository.SettlementRepository;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;

import java.util.Map;

/**
 * Fresh settlement requires a healthy backend. Durable retries reach the journal
 * during outages, where they can return protocol pending without broadcasting again.
 * Verification stays in-band because it makes no state change.
 */
public class SettlementGate {

    private final Map<String, FacilitatorChainService> chainsByNetwork;

    private final SettlementRepository repo;
    private final CardanoTransactionDecoder decoder;

    public SettlementGate(Map<String, FacilitatorChainService> chainsByNetwork) {
        this(chainsByNetwork, null, null);
    }

    public SettlementGate(Map<String, FacilitatorChainService> chainsByNetwork,
                          SettlementRepository repo, CardanoTransactionDecoder decoder) {
        this.chainsByNetwork = chainsByNetwork;
        this.repo = repo;
        this.decoder = decoder;
    }

    /** Durable retries must reach the journal even while a provider is unavailable. */
    public boolean isHealthy(String network, PaymentPayload payload) {
        if (repo != null && decoder != null && payload != null && payload.payload() != null
                && payload.payload().get("transaction") instanceof String transaction) {
            try {
                if (repo.find(decoder.decode(transaction).txHashHex().toLowerCase()).isPresent()) return true;
            } catch (Exception ignored) {
                // Malformed input or journal lookup failure does not bypass the health gate.
            }
        }
        return isHealthy(network);
    }

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
