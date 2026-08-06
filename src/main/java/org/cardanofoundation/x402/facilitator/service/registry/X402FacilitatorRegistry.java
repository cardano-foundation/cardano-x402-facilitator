package org.cardanofoundation.x402.facilitator.service.registry;

import org.cardanofoundation.x402.facilitator.model.protocol.SupportedKind;
import org.cardanofoundation.x402.facilitator.model.protocol.SupportedResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class X402FacilitatorRegistry {

    private record Key(String scheme, String network) {
    }

    private final Map<Key, SchemeNetworkFacilitator> handlers = new LinkedHashMap<>();

    public void register(String network, SchemeNetworkFacilitator facilitator) {
        handlers.put(new Key(facilitator.scheme(), CardanoNetworks.normalize(network)), facilitator);
    }

    public Optional<SchemeNetworkFacilitator> find(int x402Version, String scheme, String network) {
        if (x402Version != 2) return Optional.empty();
        return Optional.ofNullable(handlers.get(new Key(scheme, CardanoNetworks.normalize(network))));
    }

    public SupportedResponse supported() {
        List<SupportedKind> kinds = handlers.keySet().stream()
                .map(k -> new SupportedKind(2, k.scheme(), k.network(), cardanoCapabilities())).toList();
        Map<String, List<String>> signers = new LinkedHashMap<>();
        handlers.values().forEach(h -> signers.put(h.caipFamily(), List.of()));
        return new SupportedResponse(kinds, List.of(), signers);
    }

    /**
     * Capabilities this facilitator advertises for a Cardano kind. A resource
     * server matches its selected policies against these before serving a 402,
     * so an omitted capability reads as "not offered" rather than "unknown".
     *
     * @return the `/supported` extra block for a Cardano kind.
     */
    private static java.util.Map<String, Object> cardanoCapabilities() {
        java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
        extra.put("assetTransferMethods", java.util.List.of("default", "masumi", "script"));
        // Hydra needs head-authenticated evidence this facilitator cannot produce.
        extra.put("settlementLayers", java.util.List.of("l1"));
        // The client builds and signs the whole transaction, balancing the fee
        // against its own inputs; this facilitator only broadcasts.
        extra.put("areFeesSponsored", false);
        // Only server submission is offered: client mode needs authenticated
        // evidence for a transaction this facilitator never broadcast, and
        // advertising a mode it cannot honour would invite 402s nobody can settle.
        extra.put("submissionModes", java.util.List.of("server"));
        // Mempool-only evidence is never treated as settled, so the floor is
        // canonical inclusion rather than -1.
        extra.put("l1Confirmations", java.util.Map.of(
                "server", java.util.Map.of("minimum", 0, "maximum", 20)));
        return extra;
    }
}
