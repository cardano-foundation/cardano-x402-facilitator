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
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.Case;
import org.cardanofoundation.x402.facilitator.e2e.kit.Funding;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.TransferAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.x402.facilitator.e2e.kit.TransferAssert.decode;
import static org.cardanofoundation.x402.facilitator.e2e.kit.TransferAssert.unchecked;


/**
 * Every error code a healthy devnet can produce, one case per (code, trigger).
 * Each case pays from its own fresh wallet (or, when it never spends, from the one shared
 * base payment built once) and:
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

    /** The shared base must outlive the whole class: near the maxTimeoutSeconds bound, like ChainOutage's. */
    private static final long SHARED_TTL_SLOTS = 550;

    private static DevnetKit kit;
    private static PaymentFactory payments;
    private static Wallet receiver;
    private static Wallet sharedPayer;
    /** One payment for every case that only edits the envelope, requirements, decoding or recipient. */
    private static Payment sharedBase;
    /** The tip slot at which {@link #sharedBase} was started; its ttl runs out SHARED_TTL_SLOTS later. */
    private static long sharedBaseFromSlot;
    private static final Object SHARED_LOCK = new Object();

    @BeforeAll
    static void stack() {
        kit = DevnetKit.fromEnv();
        payments = new PaymentFactory(kit);
        receiver = Case.standardReceiver();
        sharedPayer = Funding.wallet(20);
        sharedBase();
    }

    /**
     * The shared base, rebuilt when its ttl is within a minute of running out: a slow run must not
     * turn every later control into ttl_expired. Safe to rebuild because the shared payer never spends.
     */
    private static Payment sharedBase() {
        synchronized (SHARED_LOCK) {
            long tip = kit.tipSlot();
            if (sharedBase == null || tip > sharedBaseFromSlot + SHARED_TTL_SLOTS - 60) {
                sharedBase = payments.from(sharedPayer).payTo(receiver).lovelace(2_000_000)
                        .ttlSlots(SHARED_TTL_SLOTS).build();
                sharedBaseFromSlot = tip;
            }
            return sharedBase;
        }
    }

    /** A case that mutates or spends: pays from its own wallet. */
    private static Case row(String code, String name, BiFunction<Payment, Wallet, Payment> defect) {
        return Case.expect(code, name).defect(defect);
    }

    /** A case that never spends: starts from the one shared base payment. */
    private static Case shared(String code, String name, BiFunction<Payment, Wallet, Payment> defect) {
        return Case.expect(code, name).sharedBase().base(w -> sharedBase()).defect(defect);
    }

    static Stream<Case> cases() {
        return Stream.of(
                // ---- envelope ----
                shared("unsupported_scheme", "accepted.scheme differs", (p, w) -> p)
                        .body(body -> { accepted(body).put("scheme", "upto"); return body; }),
                shared("unsupported_scheme", "unknown assetTransferMethod",
                        (p, w) -> p.withExtra("assetTransferMethod", "teleport")),
                shared("network_mismatch", "accepted.network differs", (p, w) -> p)
                        .body(body -> { accepted(body).put("network", "cardano:preprod"); return body; }),
                shared("invalid_exact_cardano_payload", "empty nonce",
                        (p, w) -> p.withPayload("nonce", "")),
                shared("invalid_exact_cardano_payload", "empty transaction",
                        (p, w) -> p.withPayload("transaction", "")),
                shared("invalid_exact_cardano_payload_nonce_invalid", "malformed nonce",
                        (p, w) -> p.withPayload("nonce", "not-a-utxo-ref")),
                shared("invalid_exact_cardano_payload_nonce_not_in_inputs", "nonce is not an input",
                        (p, w) -> p.withPayload("nonce", "ab".repeat(32) + "#0")),
                row("invalid_exact_cardano_payload_nonce_not_on_chain", "nonce already spent",
                        (p, w) -> spendEverything(p, w)),
                Case.expect("invalid_exact_cardano_payload_input_not_available", "second input already spent")
                        .base(RejectionMatrixE2ETest::twoInputBase).defect(RejectionMatrixE2ETest::spendSecondInput),

                // ---- requirements ----
                shared("invalid_exact_cardano_requirements", "amount 0",
                        (p, w) -> p.withRequirement("amount", "0")),
                shared("invalid_exact_cardano_requirements", "amount with leading zero",
                        (p, w) -> p.withRequirement("amount", "02000000")),
                shared("invalid_exact_cardano_requirements", "maxTimeoutSeconds 0",
                        (p, w) -> p.withRequirement("maxTimeoutSeconds", 0)),
                shared("invalid_exact_cardano_requirements", "non-canonical asset",
                        (p, w) -> p.withRequirement("asset", "ab.cd")),
                shared("invalid_exact_cardano_requirements_policy", "confirmationPolicy above 20",
                        (p, w) -> p.withExtra("confirmationPolicy", Map.of("l1Confirmations", 21))),
                shared("invalid_exact_cardano_requirements_policy", "confirmationPolicy with an unknown member",
                        (p, w) -> p.withExtra("confirmationPolicy", Map.of("l1Confirmations", 1, "extra", 1))),
                shared("invalid_exact_cardano_payload", "payTo on mainnet",
                        (p, w) -> p.withRequirement("payTo", new Account(Networks.mainnet()).baseAddress())),

                // ---- decoding ----
                shared("invalid_exact_cardano_payload_transaction_decode_failed", "transaction is not base64",
                        (p, w) -> p.withPayload("transaction", "!!not-base64!!")),
                shared("invalid_exact_cardano_payload_transaction_decode_failed", "transaction is not a CBOR tx",
                        (p, w) -> p.withPayload("transaction", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}))),

                // ---- size (read against live max_tx_size, see oversize) ----
                // The control is the same payment unpadded; the defect is the padded one.
                Case.expect("invalid_exact_cardano_payload", "tx above protocol max_tx_size")
                        .base(w -> padded(w, 10)).defect((p, w) -> oversize(w, false))
                        .message("transaction exceeds maxTxSize"),
                Case.expect("invalid_exact_cardano_payload", "tx above the facilitator's 32 KiB cap")
                        .base(w -> padded(w, 10)).defect((p, w) -> oversize(w, true))
                        .message("transaction exceeds max-tx-bytes"),

                // ---- signatures ----
                row("invalid_exact_cardano_payload_unsigned", "no witnesses",
                        (p, w) -> payments.mutate(p, tx -> { }, List.of())),
                row("invalid_exact_cardano_payload_invalid_signature", "witness does not cover the body",
                        (p, w) -> payments.mutateKeepingWitnesses(p, tx -> shiftFee(tx, w, 1))),
                row("invalid_exact_cardano_payload_payer_not_witness", "signed by a stranger, not the payer",
                        (p, w) -> payments.mutate(p, tx -> { }, List.of(new Account(Networks.testnet())))),

                // ---- network ----
                row("invalid_exact_cardano_payload_network_id_mismatch", "body network_id is mainnet",
                        (p, w) -> payments.mutateAndResign(p, tx -> tx.getBody().setNetworkId(NetworkId.MAINNET))),
                row("invalid_exact_cardano_payload_network_id_mismatch", "an output is tagged for mainnet",
                        (p, w) -> payments.mutateAndResign(p, tx ->
                                changeOutput(tx, w).setAddress(new Account(Networks.mainnet()).baseAddress()))),

                // ---- phase 1 / phase 2 ----
                row("invalid_exact_cardano_payload_phase1_invalid", "duplicate input",
                        (p, w) -> payments.mutateAndResign(p, tx -> {
                            TransactionInput first = tx.getBody().getInputs().get(0);
                            tx.getBody().getInputs().add(new TransactionInput(first.getTransactionId(), first.getIndex()));
                        })),
                row("invalid_exact_cardano_payload_phase1_invalid", "required signer without a witness",
                        (p, w) -> payments.mutateAndResign(p, tx -> {
                            shiftFee(tx, w, 20_000); // pays for the added bytes, so the fee check is not what fires
                            tx.getBody().setRequiredSigners(List.of(new Account(Networks.testnet()).hdKeyPair().getPublicKey().getKeyHash()));
                        })),
                row("invalid_exact_cardano_payload_phase1_invalid", "an input locked by a script",
                        (p, w) -> addScriptInput(p, w)),
                row("invalid_exact_cardano_payload_phase2_invalid", "is_valid false",
                        (p, w) -> payments.mutateAndResign(p, tx -> tx.setValid(false))),

                // ---- validity interval ----
                row("invalid_exact_cardano_payload_ttl_expired", "ttl already passed",
                        (p, w) -> payments.mutateAndResign(p, tx -> tx.getBody().setTtl(kit.tipSlot() - 1))),
                row("invalid_exact_cardano_payload_ttl_too_far", "ttl beyond maxTimeoutSeconds",
                        (p, w) -> payments.mutateAndResign(p, tx -> tx.getBody().setTtl(kit.tipSlot()
                                + PaymentFactory.DEFAULT_MAX_TIMEOUT_SECONDS + 300))),
                row("invalid_exact_cardano_payload_not_yet_valid", "validity starts in the future",
                        (p, w) -> payments.mutateAndResign(p, tx -> tx.getBody().setValidityStartInterval(kit.tipSlot() + 120))),
                row("invalid_exact_cardano_payload", "no ttl",
                        (p, w) -> payments.mutateAndResign(p, tx -> tx.getBody().setTtl(0))),

                // ---- value and fees ----
                row("invalid_exact_cardano_payload_value_not_conserved", "outputs exceed inputs",
                        (p, w) -> payments.mutateAndResign(p, tx -> addCoin(changeOutput(tx, w), 1_000))),
                row("invalid_exact_cardano_payload_fee_below_minimum", "fee below the minimum",
                        (p, w) -> payments.mutateAndResign(p, tx -> shiftFee(tx, w, -20_000))),
                row("invalid_exact_cardano_payload_min_utxo_insufficient", "an output below min-UTxO",
                        (p, w) -> payments.mutateAndResign(p, tx -> {
                            TransactionOutput paid = paidOutput(tx);
                            BigInteger moved = paid.getValue().getCoin().subtract(BigInteger.valueOf(500_000));
                            addCoin(paid, moved.negate().longValueExact());
                            addCoin(changeOutput(tx, w), moved.longValueExact());
                        }).withRequirement("amount", "500000")),

                // ---- recipient and amount ----
                shared("invalid_exact_cardano_payload_recipient_mismatch", "payTo is someone else",
                        (p, w) -> p.withRequirement("payTo", new Account(Networks.testnet()).baseAddress())),
                shared("invalid_exact_cardano_payload_asset_mismatch", "asset is a token the tx does not carry",
                        (p, w) -> p.withRequirement("asset", "cd".repeat(28) + "." + "e2e0")),
                shared("invalid_exact_cardano_payload_amount_insufficient", "amount above what is paid",
                        (p, w) -> p.withRequirement("amount", "2000001"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void rejects(Case c) {
        Wallet payer = c.usesSharedBase() ? sharedPayer : Funding.wallet(20);

        // 1. control: the unmutated base (or, for oversize, the same payment unpadded) is valid
        Payment control = c.base().apply(payer);
        TransferAssert.assertControlValid(c, control);

        // 2 + 3. exactly one defect -> exactly this code on /verify, and on /settle with nothing submitted
        Payment defective = c.defect().apply(control, payer);
        TransferAssert.assertCase(c, defective);
    }

    // ---- envelope defects that live outside the requirements both places share ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> accepted(Map<String, Object> body) {
        return (Map<String, Object>) ((Map<String, Object>) body.get("paymentPayload")).get("accepted");
    }

    // ---- bases ----

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
}
