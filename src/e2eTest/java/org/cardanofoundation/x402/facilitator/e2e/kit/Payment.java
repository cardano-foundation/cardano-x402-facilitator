package org.cardanofoundation.x402.facilitator.e2e.kit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One signed x402 payment and the request that carries it. Immutable — the
 * byte array and the maps are copied in and out — so a test can derive many
 * requests from one base without one derivation leaking into another. Key
 * order is kept, so request JSON is identical run to run.
 *
 * @param payer        the wallet that signed (and whose input is the nonce).
 * @param txBytes      the signed transaction bytes the hash was computed from.
 *                     The sent bytes are {@code payload.transaction}; the two
 *                     differ only when a test overrides that field directly.
 * @param txHash       the hash computed LOCALLY from {@code txBytes}.
 * @param requirements the payment requirements, used for both {@code accepted}
 *                     and the top-level {@code paymentRequirements}.
 * @param payload      the scheme payload ({@code transaction}, {@code nonce}).
 */
public record Payment(Wallet payer, byte[] txBytes, String txHash,
                      Map<String, Object> requirements, Map<String, Object> payload) {

    public Payment {
        txBytes = txBytes.clone();
        requirements = Collections.unmodifiableMap(deepCopy(requirements));
        payload = Collections.unmodifiableMap(deepCopy(payload));
    }

    @Override
    public byte[] txBytes() {
        return txBytes.clone();
    }

    /** A mutable copy; edits never reach this payment. */
    @Override
    public Map<String, Object> requirements() {
        return deepCopy(requirements);
    }

    /** A mutable copy; edits never reach this payment. */
    @Override
    public Map<String, Object> payload() {
        return deepCopy(payload);
    }

    public String nonce() {
        return (String) payload.get("nonce");
    }

    public String txBase64() {
        return Base64.getEncoder().encodeToString(txBytes);
    }

    /** A copy with one requirements field replaced (in both places it appears); null is kept as a JSON null. */
    public Payment withRequirement(String key, Object value) {
        Map<String, Object> copy = deepCopy(requirements);
        copy.put(key, value);
        return new Payment(payer, txBytes, txHash, copy, payload);
    }

    /** A copy without one requirements field. */
    public Payment withoutRequirement(String key) {
        Map<String, Object> copy = deepCopy(requirements);
        copy.remove(key);
        return new Payment(payer, txBytes, txHash, copy, payload);
    }

    /** A copy with one {@code requirements.extra} field replaced. */
    @SuppressWarnings("unchecked")
    public Payment withExtra(String key, Object value) {
        Object current = requirements.get("extra");
        Map<String, Object> extra = current instanceof Map<?, ?> m ? deepCopy((Map<String, Object>) m) : new LinkedHashMap<>();
        extra.put(key, value);
        return withRequirement("extra", extra);
    }

    /** A copy with one payload field replaced. */
    public Payment withPayload(String key, Object value) {
        Map<String, Object> copy = deepCopy(payload);
        copy.put(key, value);
        return new Payment(payer, txBytes, txHash, requirements, copy);
    }

    /**
     * The /verify and /settle request body, as a fresh mutable tree: a test that
     * needs an off-contract envelope (e.g. {@code accepted} differing from the
     * requirements) edits the returned map directly.
     */
    public Map<String, Object> body() {
        Map<String, Object> paymentPayload = new LinkedHashMap<>();
        paymentPayload.put("x402Version", 2);
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("url", "https://e2e.test/resource");
        resource.put("description", "devnet e2e");
        paymentPayload.put("resource", resource);
        paymentPayload.put("accepted", deepCopy(requirements));
        paymentPayload.put("payload", deepCopy(payload));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("x402Version", 2);
        body.put("paymentPayload", paymentPayload);
        body.put("paymentRequirements", deepCopy(requirements));
        return body;
    }

    /** Mutable, order-preserving deep copy of a JSON-shaped tree (maps, lists, scalars). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((k, v) -> copy.put(k, deepCopyValue(v)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> m) return deepCopy((Map<String, Object>) m);
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(deepCopyValue(item));
            return copy;
        }
        if (value instanceof byte[] bytes) return bytes.clone();
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Payment p && txHash.equals(p.txHash) && Arrays.equals(txBytes, p.txBytes)
                && requirements.equals(p.requirements) && payload.equals(p.payload);
    }

    @Override
    public int hashCode() {
        return txHash.hashCode();
    }

    @Override
    public String toString() {
        return "Payment[tx=" + txHash + ", nonce=" + nonce() + ", requirements=" + requirements + "]";
    }
}
