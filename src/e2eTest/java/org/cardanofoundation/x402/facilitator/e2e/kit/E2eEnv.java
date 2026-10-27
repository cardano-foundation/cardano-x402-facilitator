package org.cardanofoundation.x402.facilitator.e2e.kit;

import java.util.ArrayList;
import java.util.List;

/**
 * The stack coordinates e2e/run.sh exports. Read lazily, so a stackless test
 * (one without {@link StackRequired}) never trips over a missing variable.
 */
public final class E2eEnv {

    public static final String FACILITATOR_URL = "FACILITATOR_URL";
    public static final String FACILITATOR_MEMPOOL_URL = "FACILITATOR_MEMPOOL_URL";
    /** Yano's Blockfrost-compatible API, directly — never through the shim. */
    public static final String CHAIN_URL = "E2E_CHAIN_URL";
    public static final String SHIM_URL = "E2E_SHIM_URL";
    public static final String ADMIN_URL = "E2E_ADMIN_URL";

    static final List<String> REQUIRED = List.of(FACILITATOR_URL, FACILITATOR_MEMPOOL_URL, CHAIN_URL, SHIM_URL, ADMIN_URL);

    private E2eEnv() {
    }

    public static String facilitatorUrl() {
        return required(FACILITATOR_URL);
    }

    public static String facilitatorMempoolUrl() {
        return required(FACILITATOR_MEMPOOL_URL);
    }

    public static String chainUrl() {
        return required(CHAIN_URL);
    }

    public static String shimUrl() {
        return required(SHIM_URL);
    }

    public static String adminUrl() {
        return required(ADMIN_URL);
    }

    static List<String> missing() {
        List<String> missing = new ArrayList<>();
        for (String name : REQUIRED) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) missing.add(name);
        }
        return missing;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set — run the suite through e2e/run.sh --java");
        }
        return stripTrailingSlash(value);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
