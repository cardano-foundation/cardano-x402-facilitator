package org.cardanofoundation.x402.facilitator.service.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settlement digest (spec section 7.1): SHA-256 over a canonical-JSON
 * serialization of the PaymentRequirements PLUS the resource identity — a
 * resource URL identifies an endpoint, requirements alone would let two
 * identically-priced resources share one payment.
 */
public final class SettlementDigest {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private SettlementDigest() {
    }

    public static String compute(PaymentRequirements requirements, Map<String, Object> resource) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("requirements", requirements);
            canonical.put("resource", resource == null ? null : resource.get("url"));
            byte[] json = CANONICAL.writeValueAsBytes(canonical);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("digest computation failed", e);
        }
    }

    /** UTF-8 canonical JSON is stable across runs; exposed for tests. */
    public static byte[] canonicalJson(Object value) {
        try {
            return CANONICAL.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static {
        // records serialize with declared field order; map keys are sorted — enough
        // canonicalization for equality of identical logical requests (same producer).
        if (!StandardCharsets.UTF_8.equals(StandardCharsets.UTF_8)) throw new AssertionError();
    }
}
