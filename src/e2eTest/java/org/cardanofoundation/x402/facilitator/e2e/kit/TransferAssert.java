package org.cardanofoundation.x402.facilitator.e2e.kit;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assertions every transfer test class used to carry its own copy of: a payment
 * is verified and settled and found on-chain, or it is rejected with exactly one
 * code on /verify and /settle and never broadcast. Always against the default
 * facilitator instance.
 */
public final class TransferAssert {

    private TransferAssert() {
    }

    /** One client (and one devnet view) per process: a new HTTP client per assertion is costly under load. */
    private static final class Shared {
        static final FacilitatorClient FACILITATOR = FacilitatorClient.defaultInstance();
        static final DevnetKit KIT = DevnetKit.fromEnv();
    }

    private static FacilitatorClient facilitator() {
        return Shared.FACILITATOR;
    }

    private static ShimClient shim() {
        return ShimClient.fromEnv();
    }

    /** /verify accepts the payment with its payer. */
    public static void assertValid(Payment payment) {
        FacilitatorClient.Response verify = facilitator().verify(payment.body());
        assertThat(verify.status()).as("%s", verify).isEqualTo(200);
        JsonNode verified = SchemaAssert.assertMatches(SchemaAssert.VERIFY, verify);
        assertThat(verified.path("isValid").asBoolean()).as("%s", verify).isTrue();
        assertThat(verified.path("payer").asText()).isEqualTo(payment.payer().address());
    }

    /**
     * /verify accepts, /settle confirms exactly the locally hashed transaction, Yano
     * (not the facilitator) shows it in a block, and the facilitator broadcast it once.
     */
    public static void assertVerifiedAndSettled(Payment payment) {
        assertVerifiedAndSettled(payment, settled -> { });
    }

    /**
     * As {@link #assertVerifiedAndSettled(Payment)}, plus {@code extraChecks} on the
     * /settle body once it is known to be a confirmed success.
     */
    public static void assertVerifiedAndSettled(Payment payment, Consumer<JsonNode> extraChecks) {
        assertValid(payment);
        FacilitatorClient.Response settle = facilitator().settle(payment.body());
        assertThat(settle.status()).as("%s", settle).isEqualTo(200);
        JsonNode settled = SchemaAssert.assertMatches(SchemaAssert.SETTLE, settle);
        assertThat(settled.path("success").asBoolean()).as("%s", settle).isTrue();
        assertThat(settled.path("transaction").asText()).isEqualTo(payment.txHash());
        assertThat(settled.path("payer").asText()).isEqualTo(payment.payer().address());
        assertThat(settled.path("extra").path("status").asText()).isEqualTo("confirmed");
        extraChecks.accept(settled);
        Shared.KIT.assertOnChain(payment.txHash());
        assertThat(shim().submitCount(payment.txBytes())).isEqualTo(1);
    }

    /** The same defective payload must fail with exactly {@code code} on /verify and /settle, nothing broadcast. */
    public static void assertRejected(Payment defective, String code) {
        assertRejected(defective.body(), defective, code, null);
    }

    /**
     * As {@link #assertRejected(Payment, String)} for an explicit request (an edited
     * envelope or raw JSON). Where two checks share a code, {@code message} (when not
     * null) is what proves WHICH check fired.
     */
    public static void assertRejected(Object request, Payment defective, String code, String message) {
        FacilitatorClient.Response verify = facilitator().verify(request);
        assertThat(verify.status()).as("%s", verify).isEqualTo(200);
        JsonNode verified = SchemaAssert.assertMatches(SchemaAssert.VERIFY, verify);
        assertThat(verified.path("isValid").asBoolean()).as("%s", verify).isFalse();
        assertThat(verified.path("invalidReason").asText()).as("%s", verify).isEqualTo(code);
        if (message != null) assertThat(verified.path("invalidMessage").asText()).as("%s", verify).isEqualTo(message);

        FacilitatorClient.Response settle = facilitator().settle(request);
        assertThat(settle.status()).as("%s", settle).isEqualTo(200);
        JsonNode settled = SchemaAssert.assertMatches(SchemaAssert.SETTLE, settle);
        assertThat(settled.path("success").asBoolean()).as("%s", settle).isFalse();
        assertThat(settled.path("errorReason").asText()).as("%s", settle).isEqualTo(code);
        if (message != null) assertThat(settled.path("errorMessage").asText()).as("%s", settle).isEqualTo(message);
        assertThat(settled.path("transaction").asText()).isEmpty();
        assertThat(shim().submitCount(defective.txBytes())).isZero();
    }

    /**
     * Step 1 of a case: the unmutated base verifies valid (the control), so a
     * rejection can only come from the one defect.
     */
    public static void assertControlValid(Case c, Payment control) {
        FacilitatorClient.Response controlled = facilitator().verify(control.body());
        assertThat(controlled.json().path("isValid").asBoolean()).as("control for %s: %s", c, controlled).isTrue();
    }

    /**
     * Steps 2 and 3 of a case: the defective payment (the case's body edits applied)
     * is answered with {@link Case#expect()} on /verify and /settle — a rejection
     * with exactly that code, or, for {@link Case#SETTLED}, a settlement.
     */
    public static void assertCase(Case c, Payment defective) {
        if (c.expectsSettled()) {
            if (c.bodyDefect() != null || c.rawJsonEdit() != null || c.expectedMessage() != null) {
                throw new IllegalArgumentException("a SETTLED case cannot carry a body edit, raw JSON edit or message: " + c);
            }
            assertVerifiedAndSettled(defective);
        } else {
            assertRejected(requestOf(c, defective), defective, c.expect(), c.expectedMessage());
        }
    }

    /** The request a case sends: the payment's body, then the case's envelope and raw-JSON edits. */
    public static Object requestOf(Case c, Payment defective) {
        Map<String, Object> body = defective.body();
        if (c.bodyDefect() != null) body = c.bodyDefect().apply(body);
        return c.rawJsonEdit() != null ? c.rawJsonEdit().apply(FacilitatorClient.toJson(body)) : body;
    }

    // ---- helpers the transfer classes shared ----

    public static Transaction decode(byte[] txBytes) {
        return unchecked(() -> Transaction.deserialize(txBytes));
    }

    @FunctionalInterface
    public interface Checked<T> {
        T get() throws Exception;
    }

    public static <T> T unchecked(Checked<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
