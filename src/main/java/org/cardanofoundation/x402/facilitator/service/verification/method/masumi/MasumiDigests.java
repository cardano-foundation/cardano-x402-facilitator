package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import com.bloxbean.cardano.client.util.HexUtil;
import org.erdtman.jcs.JsonCanonicalizer;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two domain-separated digests the Masumi method binds a payment to:
 * {@code inputHash} over the request commitment, and {@code termsDigest} over
 * the seller-signed terms.
 *
 * <p>Both are {@code SHA-256(UTF-8(domain) || UTF-8(RFC8785-JCS(value)))}. The
 * canonicalization has to be real JCS rather than "sorted keys": JCS also fixes
 * number formatting and string escaping, and a digest that disagrees with the
 * TypeScript implementation on either would reject payments that are actually
 * valid — or, worse, accept terms the seller never signed.
 *
 * <p>Mirrors {@code digests.ts} in the reference implementation.
 */
public final class MasumiDigests {

    private static final String INPUT_HASH_DOMAIN = "masumi:x402:input:v1\n";
    private static final String TERMS_DIGEST_DOMAIN = "masumi:x402:terms:v1\n";

    private MasumiDigests() {
    }

    /**
     * Canonicalizes a JSON value per RFC 8785.
     *
     * @param value the value to canonicalize (maps, lists, strings, numbers, booleans, null).
     * @return the canonical JSON text.
     */
    static String jcs(Object value) {
        try {
            return new JsonCanonicalizer(toJson(value)).getEncodedString();
        } catch (IOException e) {
            throw new IllegalArgumentException("value is not RFC 8785-canonicalizable", e);
        }
    }

    /** {@code SHA-256(UTF-8(domain) || UTF-8(JCS(value)))} as lowercase hex. */
    private static String domainDigest(String domain, Object value) {
        byte[] prefix = domain.getBytes(StandardCharsets.UTF_8);
        byte[] body = jcs(value).getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(prefix);
            sha.update(body);
            return HexUtil.encodeHexString(sha.digest()).toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Digest of one commitment part: {@code SHA-256(partBytes)}. For {@code jcs}
     * the bytes are the canonical JSON of the content; for {@code raw} they are
     * the base64url-decoded content.
     *
     * @param part the commitment part.
     * @return lowercase hex digest.
     */
    public static String commitmentPartDigest(Map<String, Object> part) {
        String canonicalization = String.valueOf(part.get("canonicalization"));
        byte[] bytes;
        if ("raw".equals(canonicalization)) {
            bytes = Base64.getUrlDecoder().decode(String.valueOf(part.get("content")));
        } else {
            bytes = jcs(part.get("content")).getBytes(StandardCharsets.UTF_8);
        }
        try {
            return HexUtil.encodeHexString(MessageDigest.getInstance("SHA-256").digest(bytes)).toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Recomputes {@code inputHash} from a commitment. The manifest deliberately
     * carries each part's digest rather than its content, so the hash commits to
     * what was requested without depending on how it was transported.
     *
     * @param commitment the {@code inputCommitment} block.
     * @return lowercase hex digest.
     */
    public static String computeInputHash(Map<String, Object> commitment) {
        List<Object> parts = new ArrayList<>();
        Object rawParts = commitment.get("parts");
        if (rawParts instanceof List<?> list) {
            for (Object p : list) {
                if (!(p instanceof Map<?, ?> part)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", part.get("name"));
                entry.put("canonicalization", part.get("canonicalization"));
                // Absent mediaType stays absent: JCS distinguishes a missing key
                // from an explicit null, and so must the manifest.
                if (part.get("mediaType") != null) entry.put("mediaType", part.get("mediaType"));
                entry.put("digest", part.get("digest"));
                parts.add(entry);
            }
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", commitment.get("version"));
        manifest.put("algorithm", commitment.get("algorithm"));
        manifest.put("parts", parts);
        return domainDigest(INPUT_HASH_DOMAIN, manifest);
    }

    /**
     * Reconstructs the signed terms: the seller's {@code terms} plus the
     * requirements fields projected into the signature. Projecting them is what
     * stops a server re-quoting the same seller-signed terms at a different
     * price, asset, network or escrow.
     *
     * @param extra the Masumi extra block.
     * @param requirements the canonical requirements being verified.
     * @return the signed-terms map, ready to digest.
     */
    public static Map<String, Object> buildSignedTerms(Map<String, Object> extra,
                                                       PaymentRequirements requirements) {
        Map<String, Object> signed = new LinkedHashMap<>();
        Object terms = extra.get("terms");
        if (terms instanceof Map<?, ?> t) {
            t.forEach((k, v) -> signed.put(String.valueOf(k), v));
        }
        signed.put("scheme", requirements.scheme());
        signed.put("assetTransferMethod", extra.get("assetTransferMethod"));
        signed.put("network", requirements.network());
        signed.put("contractAddress", requirements.payTo());
        signed.put("amount", requirements.amount());
        signed.put("asset", requirements.asset());
        signed.put("maxTimeoutSeconds", requirements.maxTimeoutSeconds());
        return signed;
    }

    /**
     * Computes the {@code termsDigest} the seller authorizes with CIP-30 signData.
     *
     * @param signedTerms the reconstructed signed terms.
     * @return lowercase hex digest.
     */
    public static String computeTermsDigest(Map<String, Object> signedTerms) {
        return domainDigest(TERMS_DIGEST_DOMAIN, signedTerms);
    }

    /** Renders a Java value as JSON text for the canonicalizer. */
    private static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(value, sb);
        return sb.toString();
    }

    private static void writeJson(Object value, StringBuilder sb) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeJsonString(s, sb);
            case Boolean b -> sb.append(b);
            case Number n -> sb.append(n);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeJsonString(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    writeJson(e.getValue(), sb);
                }
                sb.append('}');
            }
            case Iterable<?> it -> {
                sb.append('[');
                boolean first = true;
                for (Object o : it) {
                    if (!first) sb.append(',');
                    first = false;
                    writeJson(o, sb);
                }
                sb.append(']');
            }
            default -> writeJsonString(String.valueOf(value), sb);
        }
    }

    private static void writeJsonString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
