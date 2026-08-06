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

    /**
     * Whether this operator settles on mempool evidence. It is advertised
     * because a resource server chooses `l1Confirmations` from what is offered,
     * and quoting -1 to a facilitator that refuses it produces a 402 that can
     * never settle.
     */
    private final boolean acceptMempool;

    public X402FacilitatorRegistry() {
        this(false);
    }

    public X402FacilitatorRegistry(boolean acceptMempool) {
        this.acceptMempool = acceptMempool;
    }

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
    private java.util.Map<String, Object> cardanoCapabilities() {
        java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
        extra.put("assetTransferMethods", java.util.List.of("default", "masumi", "script"));
        // Hydra needs head-authenticated evidence this facilitator cannot produce.
        extra.put("settlementLayers", java.util.List.of("l1"));
        // The client builds and signs the whole transaction, balancing the fee
        // against its own inputs; this facilitator only broadcasts.
        extra.put("areFeesSponsored", false);
        // Both modes are honoured: server submission broadcasts, client
        // submission authenticates inclusion evidence for a transaction the
        // payer already sent. A resource server may therefore also quote
        // `either` and let the payer choose.
        extra.put("submissionModes", java.util.List.of("server", "client"));
        // -1 is mempool evidence, which this facilitator can read but refuses to
        // settle on unless the operator opted in — so the advertised floor moves
        // with that setting rather than promising evidence it will not accept.
        int minimum = acceptMempool ? -1 : 0;
        java.util.Map<String, Object> range = java.util.Map.of("minimum", minimum, "maximum", 20);
        extra.put("l1Confirmations", java.util.Map.of("server", range, "client", range));
        return extra;
    }
}
