package org.cardanofoundation.x402.facilitator.e2e.kit;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Reads the shim's submit counter: how many times the facilitators POSTed a
 * given transaction to the chain. Both instances share the one shim, so a test
 * that counts must use a transaction no other test submits.
 */
public final class ShimClient {

    private final FacilitatorClient http;

    public ShimClient(String shimUrl) {
        this.http = new FacilitatorClient(shimUrl);
    }

    private static volatile ShimClient shared;

    /** The one client for the stack's shim (one HTTP client per base URL). */
    public static ShimClient fromEnv() {
        ShimClient client = shared;
        if (client == null) {
            synchronized (ShimClient.class) {
                if (shared == null) shared = new ShimClient(E2eEnv.shimUrl());
                client = shared;
            }
        }
        return client;
    }

    /** @return how often exactly these signed-transaction bytes were submitted. */
    public int submitCount(byte[] signedTx) {
        FacilitatorClient.Response response = http.get("/__shim/submits?sha256=" + sha256Hex(signedTx));
        if (response.status() != 200) {
            throw new IllegalStateException("shim counter unavailable: " + response);
        }
        JsonNode count = response.json().path("count");
        if (!count.isInt()) {
            throw new IllegalStateException("shim counter body malformed: " + response);
        }
        return count.asInt();
    }

    // ---- opt-in faults and profile (see e2e/shim/yano_genesis_shim.py); facilitator view only ----

    /**
     * Registers (or replaces, by id) a fault. {@code match} keys: txHash, sha256, pathRegex, outRef;
     * {@code action} carries {@code type} (drop-response, swallow, ledger-reject, status, mempool,
     * hide-tx, drop-lovelace) and its arguments; {@code times} (nullable) limits the firings.
     * Path-wide faults need an {@code @Isolated} class, and the caller clears them in {@code finally}.
     */
    public void registerFault(String id, Map<String, Object> match, Map<String, Object> action, Integer times) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", id);
        spec.put("match", match);
        spec.put("action", action);
        if (times != null) spec.put("times", times);
        expectOk("register fault " + id, http.post("/__shim/faults", FacilitatorClient.toJson(spec)));
    }

    public void registerFault(String id, Map<String, Object> match, Map<String, Object> action) {
        registerFault(id, match, action, null);
    }

    public void deleteFault(String id) {
        expectOk("delete fault " + id, http.send("DELETE", "/__shim/faults/" + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20"), null, Map.of()));
    }

    public void clearFaults() {
        expectOk("clear faults", http.send("DELETE", "/__shim/faults", null, Map.of()));
    }

    /** Sets the profile; any argument may be null (omitted). A latency alone adds no rate limit. */
    public void setProfile(Integer latencyMs, Double ratePerSec, Integer burst) {
        Map<String, Object> spec = new LinkedHashMap<>();
        if (latencyMs != null) spec.put("latencyMs", latencyMs);
        if (ratePerSec != null) spec.put("ratePerSec", ratePerSec);
        if (burst != null) spec.put("burst", burst);
        expectOk("set profile", http.post("/__shim/profile", FacilitatorClient.toJson(spec)));
    }

    public void clearProfile() {
        expectOk("clear profile", http.send("DELETE", "/__shim/profile", null, Map.of()));
    }

    /** Clears every fault and the profile (what IsolatedStackGuard does after an isolated class). */
    public void reset() {
        try {
            clearFaults();
        } finally {
            // A leftover rate limit would stall the guard's readiness wait.
            clearProfile();
        }
    }

    private static void expectOk(String what, FacilitatorClient.Response response) {
        if (response.status() != 200) throw new IllegalStateException(what + " failed: " + response);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
