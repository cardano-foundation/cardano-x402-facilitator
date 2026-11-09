package org.cardanofoundation.x402.facilitator.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.x402.facilitator.e2e.kit.Case;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.Funding;
import org.cardanofoundation.x402.facilitator.e2e.kit.MasumiLock;
import org.cardanofoundation.x402.facilitator.e2e.kit.MasumiLock.Deployment;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.TransferAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiBlueprint;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiConstants;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiDatum;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.x402.facilitator.e2e.kit.MasumiLock.MINUTE_MS;
import static org.cardanofoundation.x402.facilitator.e2e.kit.MasumiLock.SELLER;
import static org.cardanofoundation.x402.facilitator.e2e.kit.MasumiLock.STRANGER;
import static org.cardanofoundation.x402.facilitator.e2e.kit.MasumiLock.commitment;
import static org.cardanofoundation.x402.facilitator.e2e.kit.MasumiLock.identifier;
import static org.cardanofoundation.x402.facilitator.e2e.kit.MasumiLock.outputTo;
import static org.cardanofoundation.x402.facilitator.e2e.kit.MasumiLock.part;
import static org.cardanofoundation.x402.facilitator.e2e.kit.MasumiLock.recommit;
import static org.cardanofoundation.x402.facilitator.e2e.kit.MasumiLock.terms;


/**
 * The {@code masumi} transfer method on the devnet: real escrow locks (ADA and a
 * native token) to the fixed deployment of {@code e2e/devnet/masumi-deployment.json},
 * settled and found on-chain with their inline datum read back from Yano, plus a
 * rejection matrix with one valid-base control and exactly one defect per case
 * (rows are {@link Case}s; the lock builder is {@link MasumiLock}).
 *
 * <p>Oracle note: main code ({@code MasumiDigests}, {@code MasumiDatum},
 * {@code MasumiBlueprint}, {@code MasumiIdentifier}) and the unit suite's
 * {@code MasumiTestSeller}/{@code TestTx} are used here as the VECTOR BUILDER:
 * the seller's CIP-8 authorization, the terms/input digests, the datum and the
 * escrow address all come from them. The facilitator under test therefore
 * proves it agrees with its own builder end to end over HTTP and on a real
 * chain; WP-7's TypeScript SDK suite cross-checks the positive vectors against
 * an independent implementation.
 *
 * <p>Expected codes are string literals: ErrorCodeCoverageTest checks this
 * source for each code's string value.
 */
@StackRequired
class MasumiTransferE2ETest {

    /** Stands in for an unpaired UTF-16 surrogate, which Jackson will not write; swapped for the JSON escape. */
    private static final String LONE_SURROGATE = "LONE_SURROGATE_MARKER";

    private static DevnetKit kit;
    private static PaymentFactory payments;
    private static Deployment allowlisted;
    private static Deployment notAllowlisted;
    private static BigInteger masumiFloorLovelace;

    @BeforeAll
    static void stack() {
        kit = DevnetKit.fromEnv();
        payments = new PaymentFactory(kit);
        MasumiLock.Deployments deployments = MasumiLock.deployments();
        allowlisted = deployments.allowlisted();
        notAllowlisted = deployments.notAllowlisted();
    }

    // ---- consistency ----

    @Test
    void theCommittedDeploymentsMatchWhatMainsBlueprintDerives() {
        for (Deployment d : List.of(allowlisted, notAllowlisted)) {
            assertThat(MasumiBlueprint.escrowScriptHash(d.params())).isEqualTo(d.scriptHash());
            assertThat(MasumiBlueprint.escrowAddress(DevnetKit.NETWORK, d.params())).isEqualTo(d.escrowAddress());
        }
        assertThat(allowlisted.scriptHash()).isNotEqualTo(notAllowlisted.scriptHash());
    }

    // ---- positives ----

    @Test
    void anAdaLockIsSettledAndItsDatumIsOnChain() {
        Wallet payer = Funding.wallet(30);
        MasumiLock.Built built = lock().build(payer);
        assertVerifiedAndSettled(built, "lovelace");
    }

    @Test
    void aTokenLockIsSettledAndItsDatumIsOnChain() {
        Wallet payer = Funding.wallet(40);
        MasumiLock lock = lock().token();
        MasumiLock.Built built = lock.build(payer);
        assertThat(built.payment().requirements().get("asset")).isEqualTo(MasumiLock.mintedFor(payer.address()));
        assertVerifiedAndSettled(built, MasumiLock.mintedFor(payer.address()));
    }


    // ---- rejections ----
    static Stream<Case> cases() {
        return Stream.of(
                row("invalid_exact_cardano_requirements_masumi_schema", "an unknown field in extra",
                        k -> k.postSign = extra -> extra.put("unexpected", "field")),
                row("invalid_exact_cardano_requirements_masumi_commitment", "a commitment part whose content does not hash to its digest",
                        k -> k.preSign = extra -> {
                            part(extra).put("digest", "ab".repeat(32));
                            recommit(extra);
                        }),
                row("invalid_exact_cardano_requirements_masumi_commitment", "a commitment digest that is not the manifest's hash",
                        k -> k.preSign = extra -> {
                            commitment(extra).put("digest", "cd".repeat(32));
                            terms(extra).put("inputHash", "cd".repeat(32));
                        }),
                row("invalid_exact_cardano_requirements_masumi_deployment", "no deployment on a network without a canonical one",
                        k -> k.postSign = extra -> extra.remove("deployment")),
                row("invalid_exact_cardano_requirements_masumi_deployment", "a deployment that does not derive to payTo",
                        k -> k.postSign = extra -> extra.put("deployment", notAllowlisted.extra())),
                row("invalid_exact_cardano_requirements_masumi_seller_signature", "a signature over other terms",
                        k -> k.postSign = extra -> extra.put("referenceSignature", SELLER.signTermsHex("00".repeat(32)))),
                row("invalid_exact_cardano_requirements_masumi_seller_signature", "terms signed by a key that is not the seller",
                        k -> k.signer = STRANGER),
                row("invalid_exact_cardano_payload_masumi_contract_mismatch", "a valid deployment the facilitator does not allowlist",
                        k -> k.deployment = notAllowlisted),
                // The container has no registry validator, so ANY non-empty agent is refused.
                row("invalid_exact_cardano_requirements_masumi_agent_identifier", "any agent identifier (no registry validator configured)",
                        k -> k.agent = "cd".repeat(28)),
                row("invalid_exact_cardano_requirements_masumi_identifier", "an identifier carrying another buyer nonce",
                        k -> k.postSign = extra -> extra.put("blockchainIdentifier",
                                identifier(extra, k.escrow(), "ffffffffffffffff"))),
                row("invalid_exact_cardano_payload_masumi_escrow_output_count", "two outputs to the escrow",
                        k -> k.txEdit = MasumiTransferE2ETest::duplicateEscrowOutput),
                row("invalid_exact_cardano_payload_masumi_datum_missing", "no inline datum",
                        k -> k.noDatum = true),
                row("invalid_exact_cardano_payload_masumi_reference_script", "a reference script on the escrow output",
                        k -> k.txEdit = MasumiTransferE2ETest::addReferenceScript),
                row("invalid_exact_cardano_payload_masumi_datum_invalid", "a datum that is not a fresh FundsLocked lock",
                        k -> k.datumEdit = spec -> spec.withStateAlt(1L)),
                row("invalid_exact_cardano_payload_masumi_deadline", "the lock is paid after payByTime",
                        k -> k.payByFromNowMs = -10 * MINUTE_MS),
                row("invalid_exact_cardano_payload_masumi_deadline", "deadlines closer together than the minimum spacing",
                        k -> k.submitGapMs = MINUTE_MS),
                row("invalid_exact_cardano_payload_masumi_datum_mismatch", "a datum with another seller nonce than the terms",
                        k -> k.datumSellerNonce = "11".repeat(32)),
                row("invalid_exact_cardano_payload_masumi_datum_mismatch", "a datum whose payByTime differs from the terms",
                        k -> k.datumEdit = spec -> spec.withPayByTime(spec.payByTime().add(BigInteger.ONE))),
                // The output still carries amount + collateral, so ONLY the minimum rule is broken.
                row("invalid_exact_cardano_payload_masumi_collateral", "a positive collateral below the minimum",
                        k -> { k.collateral = 1_000_000L; k.sent = k.amount + k.collateral; }),
                row("invalid_exact_cardano_payload_masumi_collateral", "an output that is not amount plus collateral",
                        k -> k.collateral = 2_000_000L),
                row("invalid_exact_cardano_payload_masumi_asset", "more tokens locked than required",
                        MasumiLock::token, k -> k.sent = 150L),
                row("invalid_exact_cardano_payload_masumi_min_utxo", "a lock below the post-result min-UTxO floor",
                        k -> k.amount = masumiFloor().longValueExact() - 50_000L),
                row("invalid_exact_cardano_payload_verification_error", "a malformed manifest string",
                        k -> k.preSign = extra -> {
                            Map<String, Object> part = part(extra);
                            part.remove("content");
                            part.put("name", LONE_SURROGATE);
                        }).rawJson(json -> json.replace(LONE_SURROGATE, "\\ud800"))
        );
    }

    /** A rejection row: the control is the (default) lock, the defect changes the knobs of its one defect. */
    private static Case row(String code, String name, Consumer<MasumiLock> defect) {
        return row(code, name, k -> { }, defect);
    }

    /** As above with a baseline: the control is the lock with only the baseline applied. */
    private static Case row(String code, String name, Consumer<MasumiLock> baseline, Consumer<MasumiLock> defect) {
        return Case.expect(code, name).arrow("<-")
                .base(payer -> built(payer, baseline, k -> { }).payment())
                .defect((control, payer) -> built(payer, baseline, defect).payment());
    }

    private static MasumiLock.Built built(Wallet payer, Consumer<MasumiLock> baseline, Consumer<MasumiLock> defect) {
        MasumiLock lock = lock();
        baseline.accept(lock);
        defect.accept(lock);
        return lock.build(payer);
    }

    private static MasumiLock lock() {
        return new MasumiLock(kit, payments, allowlisted);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void rejects(Case c) {
        Wallet payer = Funding.wallet(40);

        // 1. control: the baseline alone is valid
        Payment control = c.base().apply(payer);
        TransferAssert.assertControlValid(c, control);

        // 2 + 3. exactly one defect -> exactly this code on /verify, and on /settle with nothing submitted
        Payment defective = c.defect().apply(control, payer);
        TransferAssert.assertCase(c, defective);
    }

    // ---- transaction surgery (re-signed by the payer; fee headroom is moved out of the change output) ----

    private static final long FEE_HEADROOM = 30_000L;


    private static Payment duplicateEscrowOutput(Payment payment, Wallet payer) {
        String escrow = (String) payment.requirements().get("payTo");
        return payments.mutateAndResign(payment, tx -> {
            TransactionOutput paid = outputTo(tx, escrow);
            TransactionOutput twin = TransactionOutput.builder().address(escrow)
                    .value(Value.builder().coin(paid.getValue().getCoin()).build()).build();
            twin.setInlineDatum(paid.getInlineDatum());
            tx.getBody().getOutputs().add(twin);
            payFromChange(tx, payer, paid.getValue().getCoin().add(BigInteger.valueOf(FEE_HEADROOM)), FEE_HEADROOM);
        });
    }

    private static Payment addReferenceScript(Payment payment, Wallet payer) {
        String escrow = (String) payment.requirements().get("payTo");
        return payments.mutateAndResign(payment, tx -> {
            outputTo(tx, escrow).setScriptRef(PlutusV3Script.builder().cborHex(TestTx.SCRIPT_CODE_V3).build());
            payFromChange(tx, payer, BigInteger.valueOf(FEE_HEADROOM), FEE_HEADROOM);
        });
    }

    /** Takes {@code take} from the change output and adds {@code feeIncrease} of it to the fee. */
    private static void payFromChange(Transaction tx, Wallet payer, BigInteger take, long feeIncrease) {
        TransactionOutput change = outputTo(tx, payer.address());
        change.getValue().setCoin(change.getValue().getCoin().subtract(take));
        tx.getBody().setFee(tx.getBody().getFee().add(BigInteger.valueOf(feeIncrease)));
    }

    /** The post-result floor the facilitator enforces on a default lock's datum, per the live protocol parameters. */
    private static BigInteger masumiFloor() {
        if (masumiFloorLovelace == null) {
            Wallet probe = new Wallet(new Account(Networks.testnet()));
            PlutusData datum = new ProbeLock().datumFor(probe);
            int datumBytes = HexUtil.decodeHexString(datum.serializeToHex()).length;
            masumiFloorLovelace = MasumiConstants.masumiMinUtxoLovelace(datumBytes, 0,
                    new BigInteger(kit.protocolParams().getCoinsPerUtxoSize()));
        }
        return masumiFloorLovelace;
    }

    /** The datum of a default lock for any wallet, without touching the chain. */
    private static final class ProbeLock {
        PlutusData datumFor(Wallet payer) {
            TestTx.MasumiSpec spec = new TestTx.MasumiSpec(BigInteger.valueOf(5_000_000L), payer.address(), false,
                    SELLER.sellerAddress, false, 0L, 19, SELLER.referenceKeyHex(),
                    SELLER.signTermsHex("00".repeat(32)), "88".repeat(32), "1122334455667788", "",
                    BigInteger.ZERO, "ab".repeat(32), "", BigInteger.valueOf(System.currentTimeMillis()),
                    BigInteger.valueOf(System.currentTimeMillis()), BigInteger.valueOf(System.currentTimeMillis()),
                    BigInteger.valueOf(System.currentTimeMillis()), 0L, false, null, null);
            return TestTx.buildMasumiDatum(spec);
        }
    }
    // ---- positives' assertions ----

    private static void assertVerifiedAndSettled(MasumiLock.Built built, String asset) {
        TransferAssert.assertVerifiedAndSettled(built.payment());
        assertEscrowUtxoOnChain(built, asset);
    }

    /** Reads the escrow UTxO back from Yano directly and compares its inline datum with the one that was built. */
    private static void assertEscrowUtxoOnChain(MasumiLock.Built built, String asset) {
        Payment payment = built.payment();
        String escrow = (String) payment.requirements().get("payTo");
        Utxo locked = kit.utxos(escrow).stream().filter(u -> u.getTxHash().equals(payment.txHash())).findFirst()
                .orElseThrow(() -> new AssertionError("no escrow UTxO from " + payment.txHash() + " at " + escrow));
        assertThat(locked.getInlineDatum()).as("inline datum of %s#%s", locked.getTxHash(), locked.getOutputIndex()).isNotNull();
        PlutusData onChain = deserialize(locked.getInlineDatum());
        assertThat(onChain.serializeToHex()).isEqualTo(built.datum().serializeToHex());

        MasumiDatum.MasumiDatumView view = MasumiDatum.parse(onChain);
        assertThat(view).isNotNull();
        assertThat(view.state()).isZero();
        assertThat(view.resultHash()).isEmpty();
        assertThat(view.referenceSignature()).isEqualTo(built.extra().get("referenceSignature"));
        assertThat(view.sellerNonce()).isEqualTo(terms(built.extra()).get("sellerNonce"));
        assertThat(view.inputHash()).isEqualTo(terms(built.extra()).get("inputHash"));
        assertThat(view.payByTime()).isEqualTo(new BigInteger((String) terms(built.extra()).get("payByTime")));
        assertThat(view.buyer().payment().hash())
                .isEqualTo(MasumiDatum.addressCredentials(payment.payer().address()).payment().hash());

        if ("lovelace".equals(asset)) {
            assertThat(locked.getAmount()).hasSize(1);
            assertThat(locked.getAmount().get(0).getQuantity()).isEqualTo(new BigInteger((String) payment.requirements().get("amount")));
        } else {
            String unit = asset.replace(".", "");
            assertThat(locked.getAmount().stream().filter(a -> unit.equals(a.getUnit())).findFirst().orElseThrow().getQuantity())
                    .isEqualTo(new BigInteger((String) payment.requirements().get("amount")));
            assertThat(locked.getAmount().stream().filter(a -> "lovelace".equals(a.getUnit())).findFirst().orElseThrow().getQuantity())
                    .isEqualTo(view.collateralReturnLovelace());
        }
    }

    private static PlutusData deserialize(String hex) {
        try {
            return PlutusData.deserialize(HexUtil.decodeHexString(hex));
        } catch (Exception e) {
            throw new IllegalStateException("not a Plutus datum: " + hex, e);
        }
    }
}
