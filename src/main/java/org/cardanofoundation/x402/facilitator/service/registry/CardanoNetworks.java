package org.cardanofoundation.x402.facilitator.service.registry;

import java.util.Map;
import java.util.Set;

public final class CardanoNetworks {

    public static final String MAINNET = "cardano:mainnet";
    public static final String PREPROD = "cardano:preprod";
    public static final String PREVIEW = "cardano:preview";

    private static final Map<String, String> CIP34_ALIASES = Map.of(
            "cip34:1-764824073", MAINNET,
            "cip34:0-1", PREPROD,
            "cip34:0-2", PREVIEW);

    private static final Set<String> SUPPORTED = Set.of(MAINNET, PREPROD, PREVIEW);

    private CardanoNetworks() {
    }

    public static String normalize(String network) {
        // Exact alias-map lookup only — TS normalizeCardanoNetwork does no case or
        // whitespace folding, and neither may we ("CARDANO:PREPROD" is rejected).
        if (network == null) return null;
        return CIP34_ALIASES.getOrDefault(network, network);
    }

    public static boolean isSupported(String network) {
        String n = normalize(network);
        return n != null && SUPPORTED.contains(n);
    }

    public static int networkId(String network) {
        return MAINNET.equals(normalize(network)) ? 1 : 0;
    }
}
