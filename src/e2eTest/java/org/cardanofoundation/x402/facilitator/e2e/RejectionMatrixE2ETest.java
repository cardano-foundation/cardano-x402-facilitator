package org.cardanofoundation.x402.facilitator.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.fasterxml.jackson.databind.JsonNode;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.SchemaAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.ShimClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every error code a healthy devnet can produce, one case per (code, trigger).
 * Each case pays from its own fresh wallet and:
 * <ol>
 *   <li>proves its unmutated base verifies valid (the control),</li>
 *   <li>applies exactly ONE defect and asserts /verify names exactly that code,</li>
 *   <li>sends the same defective payload to /settle and asserts the same code,
 *       an empty {@code transaction}, and that nothing was broadcast.</li>
 * </ol>
 * Expected codes are written as literals: ErrorCodeCoverageTest checks this
 * source for each code's string value.
 */
@StackRequired
class RejectionMatrixE2ETest {

    private static DevnetKit kit;
    private static PaymentFactory payments;
    private static FacilitatorClient facilitator;
    private static ShimClient shim;
    private static Wallet receiver;

    @BeforeAll
    static void stack() {
        kit = DevnetKit.fromEnv();
        payments = new PaymentFactory(kit);
        facilitator = FacilitatorClient.defaultInstance();
        shim = ShimClient.fromEnv();
        receiver = kit.newWallet(5);
    }

    /**
     * One rejection vector: the valid control, the base the defect starts from
     * (the control itself, except for the oversize cases), and the one defect.
     */
    record Case(String name, String code, Function<Wallet, Payment> control, Function<Wallet, Payment> base,
                BiFunction<Payment, Wallet, Payment> defect) {
        Case(String name, String code, BiFunction<Payment, Wallet, Payment> defect) {
            this(name, code, RejectionMatrixE2ETest::standardBase, null, defect);
        }

        Case(String name, String code, Function<Wallet, Payment> base, BiFunction<Payment, Wallet, Payment> defect) {
            this(name, code, base, null, defect);
        }

        @Override
        public String toString() {
            return code + " ← " + name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                // ---- envelope ----
                new Case("accepted.scheme differs", "unsupported_scheme",
                        (p, w) -> p), // applied on the body, see bodyOf
                new Case("unknown assetTransferMethod", "unsupported_scheme",
                        (p, w) -> p.withExtra("assetTransferMethod", "teleport")),
                new Case("accepted.network differs", "network_mismatch",
                        (p, w) -> p), // applied on the body, see bodyOf
                new Case("empty nonce", "invalid_exact_cardano_payload",
                        (p, w) -> p.withPayload("nonce", "")),
                new Case("empty transaction", "invalid_exact_cardano_payload",
                        (p, w) -> p.withPayload("transaction", "")),
                new Case("malformed nonce", "invalid_exact_cardano_payload_nonce_invalid",
                        (p, w) -> p.withPayload("nonce", "not-a-utxo-ref")),
                new Case("nonce is not an input", "invalid_exact_cardano_payload_nonce_not_in_inputs",
                        (p, w) -> p.withPayload("nonce", "ab".repeat(32) + "#0")),
                new Case("nonce already spent", "invalid_exact_cardano_payload_nonce_not_on_chain",
                        (p, w) -> spendEverything(p, w)),
                new Case("second input already spent", "invalid_exact_cardano_payload_input_not_available",
                        RejectionMatrixE2ETest::twoInputBase, RejectionMatrixE2ETest::spendSecondInput),

                // ---- requirements ----
                new Case("amount 0", "invalid_exact_cardano_requirements",
                        (p, w) -> p.withRequirement("amount", "0")),
                new Case("amount with leading zero", "invalid_exact_cardano_requirements",
                        (p, w) -> p.withRequirement("amount", "02000000")),
                new Case("maxTimeoutSeconds 0", "invalid_exact_cardano_requirements",
                        (p, w) -> p.withRequirement("maxTimeoutSeconds", 0)),
                new Case("non-canonical asset", "invalid_exact_cardano_requirements",
                        (p, w) -> p.withRequirement("asset", "ab.cd")),
                new Case("confirmationPolicy above 20", "invalid_exact_cardano_requirements_policy",
                        (p, w) -> p.withExtra("confirmationPolicy", Map.of("l1Confirmations", 21))),
                new Case("confirmationPolicy with an unknown member", "invalid_exact_cardano_requirements_policy",
                        (p, w) -> p.withExtra("confirmationPolicy", Map.of("l1Confirmations", 1, "extra", 1))),
                new Case("payTo on mainnet", "invalid_exact_cardano_payload",
                        (p, w) -> p.withRequirement("payTo", new Account(Networks.mainnet()).baseAddress())),

                // ---- decoding ----
                new Case("transaction is not base64", "invalid_exact_cardano_payload_transaction_decode_failed",
                        (p, w) -> p.withPayload("transaction", "!!not-base64!!")),
                new Case("transaction is not a CBOR tx", "invalid_exact_cardano_payload_transaction_decode_failed",
                        (p, w) -> p.withPayload("transaction", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}))),

                // ---- size (read against live max_tx_size, see oversize) ----
                new Case("tx above protocol max_tx_size", "invalid_exact_cardano_payload",
                        w -> padded(w, 10), w -> oversize(w, false), (p, w) -> p),
                new Case("tx above the facilitator's 32 KiB cap", "invalid_exact_cardano_payload",
                        w -> padded(w, 10), w -> oversize(w, true), (p, w) -> p),

                // ---- signatures ----
                new Case("no witnesses", "invalid_exact_cardano_payload_unsigned",
                        (p, w) -> payments.mutate(p, tx -> { }, List.of())),
                new Case("witness does not cover the body", "invalid_exact_cardano_payload_invalid_signature",
                        (p, w) -> payments.mutateKeepingWitnesses(p, tx -> shiftFee(tx, w, 1))),
                new Case("signed by a stranger, not the payer", "invalid_exact_cardano_payload_payer_not_witness",
                        (p, w) -> payments.mutate(p, tx -> { }, List.of(new Account(Networks.testnet())))),

                // ---- network ----
                new Case("body network_id is mainnet", "invalid_exact_cardano_payload_network_id_mismatch",
                        (p, w) -> payments.mutateAndResign(p, tx -> tx.getBody().setNetworkId(NetworkId.MAINNET))),
                new Case("an output is tagged for mainnet", "invalid_exact_cardano_payload_network_id_mismatch",
                        (p, w) -> payments.mutateAndResign(p, tx ->
                                changeOutput(tx, w).setAddress(new Account(Networks.mainnet()).baseAddress()))),

                // ---- phase 1 / phase 2 ----
                new Case("duplicate input", "invalid_exact_cardano_payload_phase1_invalid",
                        (p, w) -> payments.mutateAndResign(p, tx -> {
                            TransactionInput first = tx.getBody().getInputs().get(0);
                            tx.getBody().getInputs().add(new TransactionInput(first.getTransactionId(), first.getIndex()));
                        })),
                new Case("required signer without a witness", "invalid_exact_cardano_payload_phase1_invalid",
                        (p, w) -> payments.mutateAndResign(p, tx -> {
                            shiftFee(tx, w, 20_000); // pays for the added bytes, so the fee check is not what fires
                            tx.getBody().setRequiredSigners(List.of(new Account(Networks.testnet()).hdKeyPair().getPublicKey().getKeyHash()));
                        })),
                new Case("an input locked by a script", "invalid_exact_cardano_payload_phase1_invalid",
                        (p, w) -> addScriptInput(p, w)),
                new Case("is_valid false", "invalid_exact_cardano_payload_phase2_invalid",
                        (p, w) -> payments.mutateAndResign(p, tx -> tx.setValid(false))),

                // ---- validity interval ----
                new Case("ttl already passed", "invalid_exact_cardano_payload_ttl_expired",
                        (p, w) -> payments.mutateAndResign(p, tx -> tx.getBody().setTtl(kit.tipSlot() - 1))),
                new Case("ttl beyond maxTimeoutSeconds", "invalid_exact_cardano_payload_ttl_too_far",
                        (p, w) -> payments.mutateAndResign(p, tx -> tx.getBody().setTtl(kit.tipSlot()
                                + PaymentFactory.DEFAULT_MAX_TIMEOUT_SECONDS + 300))),
                new Case("validity starts in the future", "invalid_exact_cardano_payload_not_yet_valid",
                        (p, w) -> payments.mutateAndResign(p, tx -> tx.getBody().setValidityStartInterval(kit.tipSlot() + 120))),
                new Case("no ttl", "invalid_exact_cardano_payload",
                        (p, w) -> payments.mutateAndResign(p, tx -> tx.getBody().setTtl(0))),

                // ---- value and fees ----
                new Case("outputs exceed inputs", "invalid_exact_cardano_payload_value_not_conserved",
                        (p, w) -> payments.mutateAndResign(p, tx -> addCoin(changeOutput(tx, w), 1_000))),
                new Case("fee below the minimum", "invalid_exact_cardano_payload_fee_below_minimum",
                        (p, w) -> payments.mutateAndResign(p, tx -> shiftFee(tx, w, -20_000))),
                new Case("an output below min-UTxO", "invalid_exact_cardano_payload_min_utxo_insufficient",
                        (p, w) -> payments.mutateAndResign(p, tx -> {
                            TransactionOutput paid = paidOutput(tx);
                            BigInteger moved = paid.getValue().getCoin().subtract(BigInteger.valueOf(500_000));
                            addCoin(paid, moved.negate().longValueExact());
                            addCoin(changeOutput(tx, w), moved.longValueExact());
                        }).withRequirement("amount", "500000")),

                // ---- recipient and amount ----
                new Case("payTo is someone else", "invalid_exact_cardano_payload_recipient_mismatch",
                        (p, w) -> p.withRequirement("payTo", new Account(Networks.testnet()).baseAddress())),
                new Case("asset is a token the tx does not carry", "invalid_exact_cardano_payload_asset_mismatch",
                        (p, w) -> p.withRequirement("asset", "cd".repeat(28) + "." + "e2e0")),
                new Case("amount above what is paid", "invalid_exact_cardano_payload_amount_insufficient",
                        (p, w) -> p.withRequirement("amount", "2000001"))
        );
    }

    /**
     * Pinned messages for cases whose code is shared by several checks: the
     * protocol-size check (min(max-tx-bytes, max_tx_size)) would also reject a tx
     * above the 32 KiB cap, so only the message shows the cap check fired.
     */
    private static final Map<String, String> MESSAGES = Map.of(
            "tx above protocol max_tx_size", "transaction exceeds maxTxSize",
            "tx above the facilitator's 32 KiB cap", "transaction exceeds max-tx-bytes");

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void rejects(Case c) {
        Wallet payer = kit.newWallet(20);
        Payment control = c.control().apply(payer);

        // 1. control: the unmutated base (or, for oversize, the same payment unpadded) is valid
        FacilitatorClient.Response controlled = facilitator.verify(control.body());
        assertThat(controlled.json().path("isValid").asBoolean()).as("control for %s: %s", c, controlled).isTrue();
        Payment base = c.base() == null ? control : c.base().apply(payer);

        // 2. exactly one defect → exactly this code on /verify
        Payment defective = c.defect().apply(base, payer);
        Map<String, Object> body = bodyOf(c, defective);
        FacilitatorClient.Response verify = facilitator.verify(body);
        assertThat(verify.status()).as("%s", verify).isEqualTo(200);
        JsonNode verified = SchemaAssert.assertMatches(SchemaAssert.VERIFY, verify);
        assertThat(verified.path("isValid").asBoolean()).as("%s", verify).isFalse();
        assertThat(verified.path("invalidReason").asText()).as("%s", verify).isEqualTo(c.code());
        // Where two checks share a code, the message is what proves WHICH check fired.
        String message = MESSAGES.get(c.name());
        if (message != null) assertThat(verified.path("invalidMessage").asText()).as("%s", verify).isEqualTo(message);

        // 3. the same payload on /settle: same code, nothing submitted
        FacilitatorClient.Response settle = facilitator.settle(body);
        assertThat(settle.status()).as("%s", settle).isEqualTo(200);
        JsonNode settled = SchemaAssert.assertMatches(SchemaAssert.SETTLE, settle);
        assertThat(settled.path("success").asBoolean()).as("%s", settle).isFalse();
        assertThat(settled.path("errorReason").asText()).as("%s", settle).isEqualTo(c.code());
        if (message != null) assertThat(settled.path("errorMessage").asText()).as("%s", settle).isEqualTo(message);
        assertThat(settled.path("transaction").asText()).isEmpty();
        assertThat(shim.submitCount(defective.txBytes())).isZero();
    }

    // ---- envelope defects that live outside the requirements both places share ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(Case c, Payment defective) {
        Map<String, Object> body = defective.body();
        Map<String, Object> accepted = (Map<String, Object>) ((Map<String, Object>) body.get("paymentPayload")).get("accepted");
        switch (c.name()) {
            case "accepted.scheme differs" -> accepted.put("scheme", "upto");
            case "accepted.network differs" -> accepted.put("network", "cardano:preprod");
            default -> { }
        }
        return body;
    }

    // ---- bases ----

    private static Payment standardBase(Wallet payer) {
        return payments.from(payer).payTo(receiver).lovelace(2_000_000).build();
    }

    /** Two payer UTxOs, both needed: the nonce is one, the other is spent behind the payment's back. */
    private static Payment twoInputBase(Wallet payer) {
        kit.topup(payer.address(), 20);
        Payment payment = payments.from(payer).payTo(receiver).lovelace(30_000_000).build();
        assertThat(decode(payment.txBytes()).getBody().getInputs()).hasSizeGreaterThanOrEqualTo(2);
        return payment;
    }

    /** A payment carrying {@code chunks} 64-byte metadata strings. */
    private static Payment padded(Wallet payer, int chunks) {
        MetadataList list = MetadataBuilder.createList();
        for (int i = 0; i < chunks; i++) list.add("x".repeat(64));
        Metadata metadata = MetadataBuilder.createMetadata();
        metadata.put(BigInteger.valueOf(674), list);
        return payments.from(payer).payTo(receiver).lovelace(2_000_000).metadata(metadata).build();
    }

    /**
     * A decodable, signed tx padded with metadata past a size limit — never an
     * undecodable blob, which would fail as decode_failed on /settle instead.
     * The protocol limit is read live, not assumed.
     */
    private static Payment oversize(Wallet payer, boolean aboveFacilitatorCap) {
        int maxTxSize = kit.protocolParams().getMaxTxSize();
        int target = aboveFacilitatorCap ? 36_000 : maxTxSize + 4_000;
        Payment payment = padded(payer, target / 66);
        int size = payment.txBytes().length;
        assertThat(size).as("padded size").isGreaterThan(maxTxSize);
        if (aboveFacilitatorCap) {
            assertThat(size).isGreaterThan(32_768);
            // Its base64 must still fit under the 64 KiB request cap, or it would be a 413.
            assertThat(payment.txBase64().length()).isLessThan(60_000);
        } else {
            assertThat(size).isLessThanOrEqualTo(32_768);
        }
        return payment;
    }

    // ---- chain side effects ----

    /** Spends every UTxO of the payer, so the base's nonce input is gone. */
    private static Payment spendEverything(Payment base, Wallet payer) {
        Tx sweep = new Tx().payToAddress(receiver.address(), Amount.ada(1)).from(payer.address());
        kit.submitAndAwait(new QuickTxBuilder(kit.getBackend()).compose(sweep)
                .withSigner(SignerProviders.signerFrom(payer.account())).buildAndSign());
        return base;
    }

    /** Spends exactly the base's non-nonce input, leaving the nonce unspent. */
    private static Payment spendSecondInput(Payment base, Wallet payer) {
        List<TransactionInput> inputs = decode(base.txBytes()).getBody().getInputs();
        String nonce = base.nonce();
        TransactionInput other = inputs.stream()
                .filter(i -> !(i.getTransactionId() + "#" + i.getIndex()).equals(nonce))
                .findFirst().orElseThrow();
        Utxo utxo = kit.utxos(payer.address()).stream()
                .filter(u -> u.getTxHash().equals(other.getTransactionId()) && u.getOutputIndex() == other.getIndex())
                .findFirst().orElseThrow();
        Tx spend = new Tx().collectFrom(List.of(utxo)).payToAddress(receiver.address(), Amount.ada(1))
                .from(payer.address());
        Transaction signed = new QuickTxBuilder(kit.getBackend()).compose(spend)
                .withSigner(SignerProviders.signerFrom(payer.account())).buildAndSign();
        // The nonce input must survive, or this would test nonce_not_on_chain instead.
        assertThat(signed.getBody().getInputs())
                .noneMatch(i -> (i.getTransactionId() + "#" + i.getIndex()).equals(nonce));
        kit.submitAndAwait(signed);
        return base;
    }

    /** Adds an unspent input locked by a native script (funded here), re-balanced and re-signed. */
    private static Payment addScriptInput(Payment base, Wallet payer) {
        ScriptPubkey lock = unchecked(() -> ScriptPubkey.create(
                VerificationKey.create(new Account(Networks.testnet()).publicKeyBytes())));
        String scriptAddress = unchecked(() -> AddressProvider.getEntAddress(lock, Networks.testnet()).toBech32());
        Utxo locked = kit.topup(scriptAddress, 3);
        return payments.mutateAndResign(base, tx -> {
            tx.getBody().getInputs().add(new TransactionInput(locked.getTxHash(), locked.getOutputIndex()));
            addCoin(changeOutput(tx, payer), 3_000_000);
            shiftFee(tx, payer, 20_000);
        });
    }

    // ---- body surgery ----

    /** Moves {@code lovelace} from the change output into the fee (or back, when negative): value stays conserved. */
    private static void shiftFee(Transaction tx, Wallet payer, long lovelace) {
        tx.getBody().setFee(tx.getBody().getFee().add(BigInteger.valueOf(lovelace)));
        addCoin(changeOutput(tx, payer), -lovelace);
    }

    private static void addCoin(TransactionOutput output, long lovelace) {
        output.getValue().setCoin(output.getValue().getCoin().add(BigInteger.valueOf(lovelace)));
    }

    private static TransactionOutput changeOutput(Transaction tx, Wallet payer) {
        return tx.getBody().getOutputs().stream()
                .filter(o -> o.getAddress().equals(payer.address()))
                .findFirst().orElseThrow(() -> new IllegalStateException("no change output in " + tx));
    }

    private static TransactionOutput paidOutput(Transaction tx) {
        return tx.getBody().getOutputs().stream()
                .filter(o -> o.getAddress().equals(receiver.address()))
                .findFirst().orElseThrow();
    }

    private static Transaction decode(byte[] txBytes) {
        return unchecked(() -> Transaction.deserialize(txBytes));
    }

    @FunctionalInterface
    private interface Checked<T> {
        T get() throws Exception;
    }

    private static <T> T unchecked(Checked<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
