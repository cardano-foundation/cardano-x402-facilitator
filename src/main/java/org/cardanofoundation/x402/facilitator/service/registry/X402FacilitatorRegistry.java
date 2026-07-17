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
                .map(k -> new SupportedKind(2, k.scheme(), k.network())).toList();
        Map<String, List<String>> signers = new LinkedHashMap<>();
        handlers.values().forEach(h -> signers.put(h.caipFamily(), List.of()));
        return new SupportedResponse(kinds, List.of(), signers);
    }
}
