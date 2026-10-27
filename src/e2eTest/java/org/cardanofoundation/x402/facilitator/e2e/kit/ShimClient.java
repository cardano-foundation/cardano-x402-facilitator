package org.cardanofoundation.x402.facilitator.e2e.kit;

import com.fasterxml.jackson.databind.JsonNode;

import java.security.MessageDigest;
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

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
