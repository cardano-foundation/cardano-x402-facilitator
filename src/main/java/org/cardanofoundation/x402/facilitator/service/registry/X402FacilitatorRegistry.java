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
    private Map<String, Object> cardanoCapabilities() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethods", List.of("default", "masumi", "script"));
        // Hydra needs head-authenticated evidence this facilitator cannot produce.
        extra.put("settlementLayers", List.of("l1"));
        // The client builds and signs the whole transaction, balancing the fee
        // against its own inputs; this facilitator only broadcasts.
        extra.put("areFeesSponsored", false);
        extra.put("submissionModes", List.of("server", "client"));
        // -1 is mempool evidence, which this facilitator can read but refuses to
        // settle on unless the operator opted in — so the advertised floor moves
        // with that setting rather than promising evidence it would reject.
        Map<String, Object> range = Map.of("minimum", acceptMempool ? -1 : 0, "maximum", 20);
        extra.put("l1Confirmations", Map.of("server", range, "client", range));
        return extra;
    }
}
