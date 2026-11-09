package org.cardanofoundation.x402.facilitator.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.TxBuilder;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.script.NativeScript;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.x402.facilitator.e2e.kit.Case;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.Fixtures;
import org.cardanofoundation.x402.facilitator.e2e.kit.Funding;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.TransferAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.x402.facilitator.e2e.kit.TransferAssert.decode;

/**
 * One row per transaction SHAPE a client can build with CCL 0.7.2: shapes the facilitator must
 * accept (a second input as the nonce, an enterprise payer, an output that also carries a token, a
 * datum-hash script lock, an upper-case nonce) and shapes it must refuse because it cannot validate
 * them locally (certificates, withdrawals, minting, reference inputs and a bare native-script
 * witness are phase-1 invalid; a recipient split across two outputs is amount_insufficient).
 *
 * <p>Each row pays from its own fresh wallet and: (1) proves its base verifies valid, (2) applies
 * the one shape, (3) asserts it settles on the devnet, or is rejected on /verify and /settle with
 * nothing broadcast. A failing row dumps its CBOR and a description to
 * {@code e2e/.out/shapes/<name>/}. Expected codes are literals: ErrorCodeCoverageTest checks this
 * source for each code's value. The class uses no shim faults and only its own wallets.
 */
@StackRequired
class ShapeMatrixE2ETest {

    private static final Path OUT = Path.of("e2e/.out/shapes");
    private static final String SCRIPT_DATUM_HASH = "ab".repeat(32);

    private static DevnetKit kit;
    private static PaymentFactory payments;
    private static Wallet receiver;

    @BeforeAll
    static void stack() {
        kit = DevnetKit.fromEnv();
        payments = new PaymentFactory(kit);
        receiver = Case.standardReceiver();
    }

    static Stream<Case> cases() {
        return Stream.of(
                // ---- accepted ----
                Case.expect(Case.SETTLED, "multi-input, nonce is the second input")
                        .base(ShapeMatrixE2ETest::multiInput),
                Case.expect(Case.SETTLED, "enterprise-address payer")
                        .base(w -> payments.from(Fixtures.enterpriseWallet(kit, 20)).payTo(receiver).lovelace(2_000_000).build()),
                Case.expect(Case.SETTLED, "payTo output also carries another token")
                        .base(ShapeMatrixE2ETest::outputWithAnotherToken),
                Case.expect(Case.SETTLED, "uppercase payload nonce")
                        .defect((p, w) -> p.withPayload("nonce", p.nonce().toUpperCase(Locale.ROOT))),
                Case.expect(Case.SETTLED, "script payment with a datum hash")
                        .base(ShapeMatrixE2ETest::scriptWithDatumHash),

                // ---- refused: amount ----
                Case.expect("invalid_exact_cardano_payload_amount_insufficient", "payTo split into two half outputs")
                        .defect((p, w) -> {
                            Payment split = pay(w, spec -> spec.preBalance(splitPaidOutputInHalf(receiver.address())));
                            assertThat(decode(split.txBytes()).getBody().getOutputs())
                                    .filteredOn(o -> o.getAddress().equals(receiver.address())).hasSize(2);
                            return split;
                        }),

                // ---- refused: not plain, and this facilitator runs no Phase1Validator ----
                Case.expect("invalid_exact_cardano_payload_phase1_invalid", "plus a stake withdrawal")
                        .defect((p, w) -> {
                            String stake = Fixtures.registeredStake(kit, w);
                            return pay(w, spec -> spec.customize(tx -> tx.withdraw(stake, BigInteger.ZERO)));
                        }),
                Case.expect("invalid_exact_cardano_payload_phase1_invalid", "plus a stake registration certificate")
                        .defect((p, w) -> pay(w, spec -> spec.customize(
                                tx -> tx.registerStakeAddress(w.account().stakeAddress())))),
                Case.expect("invalid_exact_cardano_payload_phase1_invalid", "plus a mint")
                        .defect((p, w) -> mintWithoutScriptWitness(w)),
                Case.expect("invalid_exact_cardano_payload_phase1_invalid", "plus a reference input")
                        .defect((p, w) -> {
                            Utxo reference = Fixtures.referenceUtxo(kit);
                            return pay(w, spec -> spec.preBalance(addReferenceInput(reference)));
                        }),
                Case.expect("invalid_exact_cardano_payload_phase1_invalid", "plus a bare native-script witness")
                        .defect((p, w) -> pay(w, spec -> spec.preBalance(addNativeScriptWitness(Fixtures.nativePolicy(w)))))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void shapes(Case c) {
        Wallet payer = Funding.wallet(20);
        Payment last = null;
        try {
            // 1. control: the base is valid
            Payment control = c.base().apply(payer);
            last = control;
            TransferAssert.assertControlValid(c, control);
            // 2 + 3. the shape: settled, or exactly this code on /verify and /settle with nothing submitted
            Payment defective = c.defect().apply(control, payer);
            last = defective;
            TransferAssert.assertCase(c, defective);
        } catch (Throwable failure) {
            dump(c, last, failure);
            throw failure;
        }
    }

    // ---- bases ----

    private static Payment pay(Wallet payer, Consumer<PaymentFactory.Spec> shape) {
        PaymentFactory.Spec spec = payments.from(payer).payTo(receiver).lovelace(2_000_000);
        shape.accept(spec);
        return spec.build();
    }

    /**
     * A mint (body key 9) whose policy script is NOT in the witness set, so the mint alone makes the
     * transaction non-plain. The facilitator checks no ledger witness requirements and verify never submits.
     */
    private static Payment mintWithoutScriptWitness(Wallet payer) {
        Payment payment = pay(payer, spec -> spec
                .customize(tx -> tx.mintAssets(Fixtures.nativePolicy(payer), new Asset("shape", BigInteger.valueOf(5)), payer.address()))
                .preBalance((context, tx) -> {
                    if (tx.getWitnessSet() != null) tx.getWitnessSet().setNativeScripts(new ArrayList<>());
                }));
        Transaction tx = decode(payment.txBytes());
        assertThat(tx.getBody().getMint()).as("mint (body key 9)").isNotEmpty();
        assertThat(tx.getWitnessSet().getNativeScripts()).as("native-script witnesses").isNullOrEmpty();
        return payment;
    }

    /** The payer holds two UTxOs and spends both; the nonce names the second input of the built body. */
    private static Payment multiInput(Wallet payer) {
        Fixtures.secondUtxo(kit, payer, 20);
        List<Utxo> both = kit.utxos(payer.address());
        assertThat(both).hasSize(2);
        Payment payment = pay(payer, spec -> spec.customize(tx -> tx.collectFrom(both)).nonceInput(1));
        List<TransactionInput> inputs = decode(payment.txBytes()).getBody().getInputs();
        assertThat(inputs).hasSize(2);
        assertThat(payment.nonce()).isEqualTo(inputs.get(1).getTransactionId().toLowerCase() + "#" + inputs.get(1).getIndex());
        return payment;
    }

    /**
     * A decoy output takes the 2 ADA the builder always adds; the output to the real payTo carries
     * 2 ADA AND a token, so the facilitator's per-output check is the one under test.
     */
    private static Payment outputWithAnotherToken(Wallet payer) {
        String token = Fixtures.mintedToken(kit, payer);
        String unit = token.replace(".", "");
        String decoy = new Account(Networks.testnet()).baseAddress();
        Payment payment = payments.from(payer).payTo(decoy).lovelace(2_000_000)
                .customize(tx -> tx.payToAddress(receiver.address(),
                        List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)), Amount.asset(unit, BigInteger.ONE))))
                .build().withRequirement("payTo", receiver.address());
        TransactionOutput carried = outputTo(payment, receiver.address());
        assertThat(carried.getValue().getMultiAssets()).isNotEmpty();
        assertThat(carried.getValue().getCoin()).isEqualTo(BigInteger.valueOf(2_000_000));
        return payment;
    }

    /**
     * The always-succeeds V3 script lock carrying a datum HASH. The builder's own output goes to the
     * receiver; the lock is the output to the script address, which the requirements then name as payTo.
     */
    private static Payment scriptWithDatumHash(Wallet payer) {
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("type", "plutusV3");
        script.put("code", TestTx.SCRIPT_CODE_V3);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("assetTransferMethod", "script");
        extra.put("script", script);
        Payment payment = pay(payer, spec -> spec.customize(tx -> tx.payToContract(TestTx.SCRIPT_ADDR_V3,
                Amount.lovelace(BigInteger.valueOf(2_000_000)), SCRIPT_DATUM_HASH)))
                .withRequirement("payTo", TestTx.SCRIPT_ADDR_V3).withRequirement("extra", extra);
        TransactionOutput lock = outputTo(payment, TestTx.SCRIPT_ADDR_V3);
        assertThat(lock.getDatumHash()).isEqualTo(HexUtil.decodeHexString(SCRIPT_DATUM_HASH));
        assertThat(lock.getInlineDatum()).isNull();
        return payment;
    }

    // ---- body fields Tx cannot express ----

    /**
     * QuickTx merges two payments to one address into a single output (checked against 0.7.2), so the
     * split is made on the body before balancing: the output to {@code address} becomes two halves.
     */
    private static TxBuilder splitPaidOutputInHalf(String address) {
        return (context, tx) -> {
            List<TransactionOutput> outputs = new ArrayList<>();
            for (TransactionOutput output : tx.getBody().getOutputs()) {
                if (!output.getAddress().equals(address)) {
                    outputs.add(output);
                    continue;
                }
                BigInteger half = output.getValue().getCoin().divide(BigInteger.TWO);
                outputs.add(TransactionOutput.builder().address(address)
                        .value(Value.builder().coin(half).build()).build());
                outputs.add(TransactionOutput.builder().address(address)
                        .value(Value.builder().coin(output.getValue().getCoin().subtract(half)).build()).build());
            }
            tx.getBody().setOutputs(outputs);
        };
    }

    private static TxBuilder addReferenceInput(Utxo reference) {
        return (context, tx) -> {
            List<TransactionInput> refs = new ArrayList<>();
            if (tx.getBody().getReferenceInputs() != null) refs.addAll(tx.getBody().getReferenceInputs());
            refs.add(new TransactionInput(reference.getTxHash(), reference.getOutputIndex()));
            tx.getBody().setReferenceInputs(refs);
        };
    }

    private static TxBuilder addNativeScriptWitness(NativeScript script) {
        return (context, tx) -> {
            if (tx.getWitnessSet() == null) tx.setWitnessSet(new TransactionWitnessSet());
            List<NativeScript> scripts = new ArrayList<>();
            if (tx.getWitnessSet().getNativeScripts() != null) scripts.addAll(tx.getWitnessSet().getNativeScripts());
            scripts.add(script);
            tx.getWitnessSet().setNativeScripts(scripts);
        };
    }

    private static TransactionOutput outputTo(Payment payment, String address) {
        Transaction tx = decode(payment.txBytes());
        return tx.getBody().getOutputs().stream().filter(o -> o.getAddress().equals(address))
                .findFirst().orElseThrow(() -> new IllegalStateException("no output to " + address));
    }

    // ---- failure evidence ----

    /** CBOR hex and a short description of the failed case, for the next person to look at. */
    private static void dump(Case c, Payment payment, Throwable failure) {
        try {
            Path dir = OUT.resolve(c.name().replaceAll("[^A-Za-z0-9._-]+", "_"));
            Files.createDirectories(dir);
            StringBuilder description = new StringBuilder()
                    .append("case: ").append(c).append('\n')
                    .append("expected: ").append(c.expect()).append('\n')
                    .append("failure: ").append(failure).append('\n');
            if (payment != null) {
                description.append("tx: ").append(payment.txHash()).append('\n')
                        .append("nonce: ").append(payment.nonce()).append('\n')
                        .append("requirements: ").append(payment.requirements()).append('\n');
                Files.writeString(dir.resolve("tx.cbor.hex"), HexUtil.encodeHexString(payment.txBytes()));
            }
            Files.writeString(dir.resolve("case.txt"), description.toString());
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }
}
