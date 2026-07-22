package org.cardanofoundation.x402.facilitator.service.verification.method.script;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV1Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.x402.facilitator.service.verification.method.ExtraValues;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reconstructs the script payment-credential (script hash) implied by a script
 * {@code extra} block and checks it matches the script credential of {@code payTo}
 * (rule S1).
 *
 * <p>Apply-params is delegated to {@code aiken-java-binding}'s UPLC engine, which
 * returns the applied script as a single CBOR-bytestring-wrapped hex — the same
 * form evolution-sdk's {@code applyParamsToScript} unwraps to before hashing.
 * cardano-client-lib's {@code getScriptHash()} expects a DOUBLE-wrapped
 * {@code cborHex} (it strips one layer, then hashes {@code langTag || inner}), so
 * the applied hex is wrapped once more before construction.
 *
 * <p><b>Parameter ordering:</b> parameters are enumerated in JS
 * {@code Object.values(parameters)} order — integer-like keys first (ascending
 * numeric), then the rest in insertion order — since clients constructing the
 * {@code extra} block use that convention. Deriving a different address than the
 * client expects would break {@code payTo} matching, so that order is replicated
 * exactly.
 */
public final class ScriptAddress {

    /**
     * True when {@code payTo} is a script address whose payment credential equals
     * the script hash reconstructed from {@code extra} (via {@code script.code} +
     * parameters, or directly from {@code scriptHash}).
     */
    public static boolean scriptAddressMatches(Map<String, Object> extra, String payTo) {
        String credential = scriptPaymentCredentialHex(payTo);
        if (credential == null) return false;
        String expected;
        try {
            expected = deriveScriptHashHex(extra);
        } catch (RuntimeException e) {
            return false;
        }
        return credential.equals(expected);
    }

    /**
     * Derives the script hash (lowercase hex) declared by a script {@code extra}
     * block. Prefers reconstructing from {@code script.code} (+ parameters); falls
     * back to {@code scriptHash} when no inline script is present.
     *
     * @throws IllegalArgumentException when neither {@code script} nor {@code scriptHash} is usable.
     */
    static String deriveScriptHashHex(Map<String, Object> extra) {
        Object scriptObj = extra == null ? null : extra.get("script");
        if (scriptObj instanceof Map<?, ?> script && script.get("code") != null) {
            String type = String.valueOf(script.get("type"));
            String code = String.valueOf(script.get("code"));
            ListPlutusData params = ListPlutusData.builder()
                    .plutusDataList(orderedParamValues(extra.get("parameters"))).build();
            String applied = AikenScriptUtil.applyParamToScript(params, code);
            return scriptHashHex(type, cborWrap(applied));
        }
        Object scriptHash = extra == null ? null : extra.get("scriptHash");
        if (scriptHash != null) {
            return String.valueOf(scriptHash).toLowerCase();
        }
        throw new IllegalArgumentException("Cardano script payment requires either `script` or `scriptHash`");
    }

    /** Script hash hex of a double-CBOR-wrapped script of the declared Plutus version. */
    private static String scriptHashHex(String type, String doubleWrappedCborHex) {
        try {
            byte[] hash = switch (type) {
                case "plutusV1" -> PlutusV1Script.builder().cborHex(doubleWrappedCborHex).build().getScriptHash();
                case "plutusV2" -> PlutusV2Script.builder().cborHex(doubleWrappedCborHex).build().getScriptHash();
                case "plutusV3" -> PlutusV3Script.builder().cborHex(doubleWrappedCborHex).build().getScriptHash();
                default -> throw new IllegalArgumentException("Unsupported Plutus script type: " + type);
            };
            return HexUtil.encodeHexString(hash).toLowerCase();
        } catch (Exception e) {
            throw new IllegalArgumentException("script hash derivation failed", e);
        }
    }

    /** Payment-credential hash of {@code payTo} when it is a script address, else null. */
    public static String scriptPaymentCredentialHex(String payTo) {
        try {
            Credential pc = new Address(payTo).getPaymentCredential().orElse(null);
            if (pc == null || pc.getType() != CredentialType.Script) return null;
            return HexUtil.encodeHexString(pc.getBytes()).toLowerCase();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Enumerates parameter values in JS {@code Object.values} order: integer-like
     * keys ascending-numeric first, then remaining keys in insertion order.
     */
    static List<PlutusData> orderedParamValues(Object parametersObj) {
        List<PlutusData> out = new ArrayList<>();
        if (!(parametersObj instanceof Map<?, ?> parameters)) return out;
        TreeMap<Long, Object> arrayKeyed = new TreeMap<>();
        List<Object> stringKeyed = new ArrayList<>();
        for (Map.Entry<?, ?> e : parameters.entrySet()) {
            String key = String.valueOf(e.getKey());
            Long idx = arrayIndex(key);
            if (idx != null) arrayKeyed.put(idx, e.getValue());
            else stringKeyed.add(e.getValue());
        }
        for (Object v : arrayKeyed.values()) out.add(toPlutusData(v));
        for (Object v : stringKeyed) out.add(toPlutusData(v));
        return out;
    }

    /** JS array-index test: canonical non-negative integer below 2^32-1. */
    private static Long arrayIndex(String key) {
        if (key.isEmpty() || (key.length() > 1 && key.charAt(0) == '0')) return null;
        for (int i = 0; i < key.length(); i++) {
            if (key.charAt(i) < '0' || key.charAt(i) > '9') return null;
        }
        try {
            long v = Long.parseLong(key);
            return v < 4294967295L ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Converts a typed script-parameter descriptor into Plutus data. */
    private static PlutusData toPlutusData(Object paramObj) {
        if (!(paramObj instanceof Map<?, ?> param)) {
            throw new IllegalArgumentException("script parameter must be an object");
        }
        String type = String.valueOf(param.get("type"));
        Object value = param.get("value");
        return switch (type) {
            case "bytes" -> BytesPlutusData.of(HexUtil.decodeHexString(String.valueOf(value)));
            case "string" -> BytesPlutusData.of(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            // BigInt(value) semantics — accepts integer Numbers and integer Strings.
            case "bigint", "integer" -> BigIntPlutusData.of(ExtraValues.toBigInteger(value));
            // JS truthiness: any non-empty string (incl. "false") / non-zero number is true.
            case "boolean" -> ConstrPlutusData.of(ExtraValues.jsTruthy(value) ? 1 : 0);
            default -> throw new IllegalArgumentException("Unsupported Cardano script parameter type: " + type);
        };
    }

    /** Wraps a hex payload in a single definite-length CBOR byte-string. */
    static String cborWrap(String hex) {
        byte[] b = HexUtil.decodeHexString(hex);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n = b.length;
        if (n < 24) {
            out.write(0x40 | n);
        } else if (n < 256) {
            out.write(0x58);
            out.write(n);
        } else if (n < 65536) {
            out.write(0x59);
            out.write((n >> 8) & 0xff);
            out.write(n & 0xff);
        } else {
            out.write(0x5a);
            out.write((n >> 24) & 0xff);
            out.write((n >> 16) & 0xff);
            out.write((n >> 8) & 0xff);
            out.write(n & 0xff);
        }
        out.write(b, 0, b.length);
        return HexUtil.encodeHexString(out.toByteArray());
    }

    private ScriptAddress() {
    }
}
