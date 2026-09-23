package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import com.bloxbean.cardano.client.address.Address;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Closed, bounded wire schema; no digest, signature or deployment work precedes this gate. */
final class MasumiSchema {
    private static final Set<String> EXTRA_KEYS = Set.of("assetTransferMethod", "confirmationPolicy",
            "areFeesSponsored", "inputCommitment", "terms", "referenceKey", "referenceSignature",
            "blockchainIdentifier", "deployment");
    private static final Set<String> COMMITMENT_KEYS = Set.of("version", "algorithm", "parts", "digest");
    private static final Set<String> PART_KEYS = Set.of("name", "canonicalization", "mediaType", "content", "digest");
    private static final Set<String> TERMS_KEYS = Set.of("version", "paymentType", "sellerAddress",
            "sellerReturnAddress", "sellerNonce", "buyerNonce", "agentIdentifier", "inputHash",
            "payByTime", "submitResultTime", "unlockTime", "externalDisputeUnlockTime");
    private static final Set<String> DEPLOYMENT_KEYS = Set.of("requiredAdmins", "adminVkeys", "cooldownPeriod");
    static final int MAX_CONTENT_BYTES = 1024 * 1024;

    private MasumiSchema() { }

    static Optional<String> validate(Map<String, Object> extra) {
        return validate(extra, CardanoNetworks.PREPROD);
    }

    static Optional<String> validate(Map<String, Object> extra, String network) {
        try {
            closed(extra, EXTRA_KEYS, "extra");
            require("masumi".equals(extra.get("assetTransferMethod")), "assetTransferMethod");
            if (extra.containsKey("areFeesSponsored")) {
                require(Boolean.FALSE.equals(extra.get("areFeesSponsored")), "areFeesSponsored");
            }
            if (extra.containsKey("confirmationPolicy")) {
                Map<?, ?> policy = object(extra.get("confirmationPolicy"), "confirmationPolicy");
                closed(policy, Set.of("l1Confirmations"), "confirmationPolicy");
                require(policy.get("l1Confirmations") instanceof Number, "l1Confirmations");
                BigInteger n = new BigDecimal(policy.get("l1Confirmations").toString()).toBigIntegerExact();
                require(n.compareTo(BigInteger.valueOf(-1)) >= 0 && n.compareTo(BigInteger.valueOf(20)) <= 0,
                        "l1Confirmations");
            }
            for (String field : List.of("referenceKey", "referenceSignature", "blockchainIdentifier")) {
                int maxBytes = field.equals("blockchainIdentifier") ? 8192 : 16384;
                require(hex(extra.get(field), 1, maxBytes * 2), field);
            }
            Map<?, ?> commitment = object(extra.get("inputCommitment"), "inputCommitment");
            closed(commitment, COMMITMENT_KEYS, "inputCommitment");
            require("1".equals(commitment.get("version")), "inputCommitment.version");
            require("sha256".equals(commitment.get("algorithm")), "inputCommitment.algorithm");
            require(hex(commitment.get("digest"), 64, 64), "inputCommitment.digest");
            require(commitment.get("parts") instanceof List<?>, "inputCommitment.parts");
            List<?> parts = (List<?>) commitment.get("parts");
            require(!parts.isEmpty() && parts.size() <= 32, "inputCommitment.parts size");
            Set<String> names = new HashSet<>();
            for (Object value : parts) {
                Map<?, ?> part = object(value, "part");
                closed(part, PART_KEYS, "part");
                require(part.get("name") instanceof String name && !name.isEmpty() && name.length() <= 128, "part.name");
                require(names.add((String) part.get("name")), "duplicate part.name");
                Object canonicalization = part.get("canonicalization");
                require("jcs".equals(canonicalization) || "raw".equals(canonicalization), "part.canonicalization");
                require(hex(part.get("digest"), 64, 64), "part.digest");
                if (part.containsKey("mediaType")) {
                    require(part.get("mediaType") instanceof String media && media.length() <= 256, "part.mediaType");
                }
                if (part.containsKey("content")) {
                    if ("raw".equals(canonicalization)) validateRaw(part.get("content"));
                    else validateJson(part.get("content"), 0, new long[2], Collections.newSetFromMap(new IdentityHashMap<>()));
                }
            }
            Map<?, ?> terms = object(extra.get("terms"), "terms");
            closed(terms, TERMS_KEYS, "terms");
            require("1".equals(terms.get("version")), "terms.version");
            require("Web3CardanoV2".equals(terms.get("paymentType")), "terms.paymentType");
            require(keyAddress(terms.get("sellerAddress"), network), "terms.sellerAddress");
            if (terms.containsKey("sellerReturnAddress")) {
                require(keyAddress(terms.get("sellerReturnAddress"), network), "terms.sellerReturnAddress");
            }
            require(hex(terms.get("sellerNonce"), 64, 64), "terms.sellerNonce");
            Object buyerNonce = terms.get("buyerNonce");
            require("".equals(buyerNonce) || hex(buyerNonce, 14, 26), "terms.buyerNonce");
            if (terms.containsKey("agentIdentifier") && terms.get("agentIdentifier") != null) {
                require(hex(terms.get("agentIdentifier"), 0, 120), "terms.agentIdentifier");
            }
            require(commitment.get("digest").equals(terms.get("inputHash")), "terms.inputHash");
            for (String field : List.of("payByTime", "submitResultTime", "unlockTime", "externalDisputeUnlockTime")) {
                require(decimal(terms.get(field), false, 20), "terms." + field);
            }
            if (extra.containsKey("deployment")) {
                Map<?, ?> d = object(extra.get("deployment"), "deployment");
                closed(d, DEPLOYMENT_KEYS, "deployment");
                require(d.get("adminVkeys") instanceof List<?>, "deployment.adminVkeys");
                List<?> keys = (List<?>) d.get("adminVkeys");
                require(!keys.isEmpty() && keys.size() <= 64, "deployment.adminVkeys size");
                for (Object key : keys) require(hex(key, 56, 56), "deployment.adminVkeys[]");
                require(decimal(d.get("requiredAdmins"), false, 3), "deployment.requiredAdmins");
                require(new BigInteger((String) d.get("requiredAdmins")).compareTo(BigInteger.valueOf(keys.size())) <= 0,
                        "deployment.requiredAdmins exceeds key count");
                require(decimal(d.get("cooldownPeriod"), true, 20), "deployment.cooldownPeriod");
            }
            return Optional.empty();
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Optional.of(e.getMessage() == null ? "invalid Masumi schema" : e.getMessage());
        }
    }

    private static boolean decimal(Object value, boolean zero, int maxChars) {
        return value instanceof String s && s.length() <= maxChars && s.matches(zero ? "0|[1-9][0-9]*" : "[1-9][0-9]*");
    }

    private static boolean hex(Object value, int minChars, int maxChars) {
        return value instanceof String s && s.length() >= minChars && s.length() <= maxChars
                && s.length() % 2 == 0 && s.matches("[0-9a-f]*");
    }

    private static boolean keyAddress(Object value, String network) {
        if (!(value instanceof String s) || s.isEmpty()) return false;
        try {
            byte[] bytes = new Address(s).getBytes();
            int type = (bytes[0] & 0xf0) >>> 4;
            return (type == 0 || type == 6) && (bytes[0] & 15) == CardanoNetworks.networkId(network);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Map<?, ?> object(Object value, String path) {
        require(value instanceof Map<?, ?>, path + " must be an object");
        return (Map<?, ?>) value;
    }

    private static void closed(Map<?, ?> object, Set<String> keys, String path) {
        require(object != null, path + " required");
        for (Object key : object.keySet()) require(keys.contains(key), path + " has unknown field " + key);
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalArgumentException(detail);
    }

    private static void validateRaw(Object content) {
        require(content instanceof String, "raw content must be unpadded base64url");
        String text = (String) content;
        require(text.length() <= (MAX_CONTENT_BYTES * 4 + 2) / 3 && text.matches("[A-Za-z0-9_-]*"), "raw content encoding or size");
        byte[] bytes = Base64.getUrlDecoder().decode(text);
        require(bytes.length <= MAX_CONTENT_BYTES && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(text),
                "raw content must be canonical base64url");
    }

    /** Depth is checked before recursion and aggregate work is bounded before digest canonicalization. */
    private static void validateJson(Object value, int depth, long[] budget, Set<Object> path) {
        require(depth <= 64 && ++budget[0] <= 100_000, "JCS nesting or value limit");
        if (value == null || value instanceof Boolean) return;
        if (value instanceof Number number) {
            require(Double.isFinite(number.doubleValue()), "JCS non-finite number");
            return;
        }
        if (value instanceof String text) {
            budget[1] += text.getBytes(StandardCharsets.UTF_8).length;
            require(budget[1] <= MAX_CONTENT_BYTES, "JCS byte limit");
            return;
        }
        require(value instanceof Map<?, ?> || value instanceof List<?>, "JCS content must be JSON");
        require(path.add(value), "JCS cycle");
        if (value instanceof List<?> list) {
            require(list.size() <= 100_000, "JCS value limit");
            for (Object item : list) validateJson(item, depth + 1, budget, path);
        } else if (value instanceof Map<?, ?> map) {
            require(map.size() <= 100_000, "JCS value limit");
            for (var entry : map.entrySet()) {
                require(entry.getKey() instanceof String, "JCS object keys must be strings");
                budget[1] += ((String) entry.getKey()).getBytes(StandardCharsets.UTF_8).length;
                require(budget[1] <= MAX_CONTENT_BYTES, "JCS byte limit");
                validateJson(entry.getValue(), depth + 1, budget, path);
            }
        }
        path.remove(value);
    }
}
