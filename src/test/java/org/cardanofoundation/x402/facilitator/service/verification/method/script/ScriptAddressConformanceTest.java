package org.cardanofoundation.x402.facilitator.service.verification.method.script;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conformance for the {@code script} method's S1 address derivation: the aiken-based
 * script-hash and address derivation must produce the expected {@code payTo} values
 * for the shared {@code MINIMAL_PLUTUS_V3} script (known-good hash/address vectors).
 */
class ScriptAddressConformanceTest {

    static Map<String, Object> scriptExtra(String type, String code) {
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("type", type);
        script.put("code", code);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "script");
        extra.put("script", script);
        return extra;
    }

    static Map<String, Object> param(String type, Object value) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("value", value);
        return p;
    }

    @Test void derivesEmptyParamsHashMatchingReference() {
        String hash = ScriptAddress.deriveScriptHashHex(scriptExtra("plutusV3", TestTx.SCRIPT_CODE_V3));
        assertThat(hash).isEqualTo(TestTx.SCRIPT_HASH_V3);
    }

    @Test void derivesParametrizedHashMatchingReference() {
        Map<String, Object> extra = scriptExtra("plutusV3", TestTx.SCRIPT_CODE_V3);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("p1", param("bigint", "42"));
        extra.put("parameters", params);
        assertThat(ScriptAddress.deriveScriptHashHex(extra)).isEqualTo(TestTx.SCRIPT_HASH_V3_INT42);
    }

    @Test void matchesReconstructedInlineScriptAddress() {
        assertThat(ScriptAddress.scriptAddressMatches(
                scriptExtra("plutusV3", TestTx.SCRIPT_CODE_V3), TestTx.SCRIPT_ADDR_V3)).isTrue();
    }

    @Test void matchesScriptHashOnly() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "script");
        extra.put("scriptHash", TestTx.SCRIPT_HASH_V3);
        assertThat(ScriptAddress.scriptAddressMatches(extra, TestTx.SCRIPT_ADDR_V3)).isTrue();
    }

    @Test void rejectsKeyCredentialPayTo() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "script");
        extra.put("scriptHash", TestTx.SCRIPT_HASH_V3);
        // PAY_TO is an enterprise KEY-credential address, not a script address.
        assertThat(ScriptAddress.scriptAddressMatches(extra, TestTx.PAY_TO)).isFalse();
    }

    @Test void rejectsMismatchedScriptHash() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "script");
        extra.put("scriptHash", "00".repeat(28));
        assertThat(ScriptAddress.scriptAddressMatches(extra, TestTx.SCRIPT_ADDR_V3)).isFalse();
    }

    @Test void isParameterSensitive() {
        Map<String, Object> extra = scriptExtra("plutusV3", TestTx.SCRIPT_CODE_V3);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("p1", param("bigint", "42"));
        extra.put("parameters", params);
        // Applying a parameter changes the hash, so it no longer matches the bare address.
        assertThat(ScriptAddress.scriptAddressMatches(extra, TestTx.SCRIPT_ADDR_V3)).isFalse();
    }

    @Test void rejectsWhenNeitherScriptNorHash() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "script");
        assertThat(ScriptAddress.scriptAddressMatches(extra, TestTx.SCRIPT_ADDR_V3)).isFalse();
    }

    @Test void enumeratesParametersInJsObjectValuesOrder() {
        // Integer-like keys first in ascending numeric order, then string keys in
        // insertion order (JS Object.values semantics).
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("b", param("bigint", "10"));
        parameters.put("1", param("bigint", "20"));
        parameters.put("a", param("bigint", "30"));
        parameters.put("0", param("bigint", "40"));
        List<PlutusData> ordered = ScriptAddress.orderedParamValues(parameters);
        assertThat(ordered).containsExactly(
                BigIntPlutusData.of(40), // key "0"
                BigIntPlutusData.of(20), // key "1"
                BigIntPlutusData.of(10), // key "b"
                BigIntPlutusData.of(30)); // key "a"
    }
}
