package org.cardanofoundation.x402.facilitator.e2e;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.SchemaAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.ShimClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code script} transfer method on the devnet: payments to always-succeeds
 * script addresses (Plutus V2 and V3, with and without applied parameters, and by
 * {@code scriptHash} alone), each settled and found in a block; plus the two
 * script-specific rejections. Scripts are only ever PAID TO, never spent.
 *
 * <p>Oracle note: the known-good vectors (code, hash, address) come from the unit
 * suite's {@code TestTx} fixtures, i.e. main-side test code is used as the vector
 * builder. WP-7's TypeScript SDK suite cross-checks positive correctness
 * independently. The container runs the default {@code reference} datum policy.
 */
@StackRequired
class ScriptTransferE2ETest {

    private static final String PARAMETERISED_ADDRESS = AddressProvider.getEntAddress(
            Credential.fromScript(HexUtil.decodeHexString(TestTx.SCRIPT_HASH_V3_INT42)), Networks.testnet()).toBech32();

    private static DevnetKit kit;
    private static PaymentFactory payments;
    private static FacilitatorClient facilitator;
    private static ShimClient shim;

    @BeforeAll
    static void stack() {
        kit = DevnetKit.fromEnv();
        payments = new PaymentFactory(kit);
        facilitator = FacilitatorClient.defaultInstance();
        shim = ShimClient.fromEnv();
    }

    @Test
    void plutusV3ScriptCode() {
        assertVerifiedAndSettled(scriptPayment(kit.newWallet(20), TestTx.SCRIPT_ADDR_V3, scriptExtra("plutusV3", false), null));
    }

    @Test
    void plutusV2ScriptCodeWithAnInlineDatum() {
        Payment payment = scriptPayment(kit.newWallet(20), TestTx.SCRIPT_ADDR_V2, scriptExtra("plutusV2", false), datum());
        assertThat(paidOutput(payment, TestTx.SCRIPT_ADDR_V2).getInlineDatum()).isNotNull();
        assertVerifiedAndSettled(payment);
    }

    @Test
    void plutusV3ScriptCodeWithAppliedParameters() {
        assertThat(PARAMETERISED_ADDRESS).isNotEqualTo(TestTx.SCRIPT_ADDR_V3);
        assertVerifiedAndSettled(scriptPayment(kit.newWallet(20), PARAMETERISED_ADDRESS, scriptExtra("plutusV3", true), null));
    }

    @Test
    void scriptHashOnly() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "script");
        extra.put("scriptHash", TestTx.SCRIPT_HASH_V3);
        assertVerifiedAndSettled(scriptPayment(kit.newWallet(20), TestTx.SCRIPT_ADDR_V3, extra, null));
    }

    @Test
    void anAddressOtherThanTheDeclaredScriptsIsRejected() {
        Wallet payer = kit.newWallet(20);
        Payment control = scriptPayment(payer, TestTx.SCRIPT_ADDR_V3, scriptExtra("plutusV3", false), null);
        assertValid(control);
        // Same transaction, but the descriptor now applies a parameter: the script's hash is no longer the paid address's.
        Payment defective = control.withExtra("parameters", scriptExtra("plutusV3", true).get("parameters"));
        assertRejected(defective, "invalid_exact_cardano_payload_script_address_mismatch");
    }

    @Test
    void aPlutusV1ScriptPaidWithAnInlineDatumIsRejected() {
        Wallet payer = kit.newWallet(20);
        Map<String, Object> extra = scriptExtra("plutusV1", false);
        Payment control = scriptPayment(payer, TestTx.SCRIPT_ADDR_V1, extra, null);
        assertValid(control);
        // Under the reference policy the one guaranteed-stranding case is rejected: V1 cannot spend an inline datum.
        Payment defective = scriptPayment(payer, TestTx.SCRIPT_ADDR_V1, extra, datum());
        assertThat(paidOutput(defective, TestTx.SCRIPT_ADDR_V1).getInlineDatum()).isNotNull();
        assertRejected(defective, "invalid_exact_cardano_payload_script_datum_missing");
    }

    // ---- vectors ----

    private static Map<String, Object> scriptExtra(String type, boolean withParameters) {
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("type", type);
        script.put("code", TestTx.SCRIPT_CODE_V3);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "script");
        extra.put("script", script);
        if (withParameters) {
            Map<String, Object> parameter = new LinkedHashMap<>();
            parameter.put("type", "bigint");
            parameter.put("value", "42");
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("p1", parameter);
            extra.put("parameters", parameters);
        }
        return extra;
    }

    private static PlutusData datum() {
        return ConstrPlutusData.of(0);
    }

    private static Payment scriptPayment(Wallet payer, String scriptAddress, Map<String, Object> extra, PlutusData inlineDatum) {
        PaymentFactory.Spec spec = payments.from(payer).payTo(scriptAddress).lovelace(2_000_000);
        if (inlineDatum != null) spec.inlineDatum(inlineDatum);
        return spec.build().withRequirement("extra", extra);
    }

    private static TransactionOutput paidOutput(Payment payment, String address) {
        try {
            return Transaction.deserialize(payment.txBytes()).getBody().getOutputs().stream()
                    .filter(o -> o.getAddress().equals(address)).findFirst().orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- assertions ----

    private static void assertValid(Payment payment) {
        FacilitatorClient.Response verify = facilitator.verify(payment.body());
        assertThat(verify.status()).as("%s", verify).isEqualTo(200);
        JsonNode verified = SchemaAssert.assertMatches(SchemaAssert.VERIFY, verify);
        assertThat(verified.path("isValid").asBoolean()).as("%s", verify).isTrue();
        assertThat(verified.path("payer").asText()).isEqualTo(payment.payer().address());
    }

    private static void assertVerifiedAndSettled(Payment payment) {
        assertValid(payment);
        FacilitatorClient.Response settle = facilitator.settle(payment.body());
        assertThat(settle.status()).as("%s", settle).isEqualTo(200);
        JsonNode settled = SchemaAssert.assertMatches(SchemaAssert.SETTLE, settle);
        assertThat(settled.path("success").asBoolean()).as("%s", settle).isTrue();
        assertThat(settled.path("transaction").asText()).isEqualTo(payment.txHash());
        assertThat(settled.path("payer").asText()).isEqualTo(payment.payer().address());
        assertThat(settled.path("extra").path("status").asText()).isEqualTo("confirmed");
        kit.assertOnChain(payment.txHash());
        assertThat(shim.submitCount(payment.txBytes())).isEqualTo(1);
    }

    /** The same defective payload must fail with exactly {@code code} on /verify and /settle, nothing broadcast. */
    private static void assertRejected(Payment defective, String code) {
        FacilitatorClient.Response verify = facilitator.verify(defective.body());
        assertThat(verify.status()).as("%s", verify).isEqualTo(200);
        JsonNode verified = SchemaAssert.assertMatches(SchemaAssert.VERIFY, verify);
        assertThat(verified.path("isValid").asBoolean()).as("%s", verify).isFalse();
        assertThat(verified.path("invalidReason").asText()).as("%s", verify).isEqualTo(code);

        FacilitatorClient.Response settle = facilitator.settle(defective.body());
        assertThat(settle.status()).as("%s", settle).isEqualTo(200);
        JsonNode settled = SchemaAssert.assertMatches(SchemaAssert.SETTLE, settle);
        assertThat(settled.path("success").asBoolean()).as("%s", settle).isFalse();
        assertThat(settled.path("errorReason").asText()).as("%s", settle).isEqualTo(code);
        assertThat(settled.path("transaction").asText()).isEmpty();
        assertThat(shim.submitCount(defective.txBytes())).isZero();
    }
}
