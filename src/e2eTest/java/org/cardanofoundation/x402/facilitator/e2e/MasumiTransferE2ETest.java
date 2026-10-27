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
import com.fasterxml.jackson.databind.JsonNode;
import org.cardanofoundation.x402.facilitator.e2e.kit.DevnetKit;
import org.cardanofoundation.x402.facilitator.e2e.kit.FacilitatorClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.Payment;
import org.cardanofoundation.x402.facilitator.e2e.kit.PaymentFactory;
import org.cardanofoundation.x402.facilitator.e2e.kit.SchemaAssert;
import org.cardanofoundation.x402.facilitator.e2e.kit.ShimClient;
import org.cardanofoundation.x402.facilitator.e2e.kit.StackRequired;
import org.cardanofoundation.x402.facilitator.e2e.kit.Wallet;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiBlueprint;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiConstants;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiDatum;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiDigests;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiIdentifier;
import org.cardanofoundation.x402.facilitator.testutil.MasumiTestSeller;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code masumi} transfer method on the devnet: real escrow locks (ADA and a
 * native token) to the fixed deployment of {@code e2e/devnet/masumi-deployment.json},
 * settled and found on-chain with their inline datum read back from Yano, plus a
 * rejection matrix with one valid-base control and exactly one defect per case.
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
    private static final long MINUTE_MS = 60_000L;

    private static final MasumiTestSeller SELLER = new MasumiTestSeller("33");
    private static final MasumiTestSeller STRANGER = new MasumiTestSeller("44");

    /** One token minted per payer wallet, shared by every build from that wallet. */
    private static final Map<String, String> MINTED = new ConcurrentHashMap<>();

    private static DevnetKit kit;
    private static PaymentFactory payments;
    private static FacilitatorClient facilitator;
    private static ShimClient shim;
    private static Deployment allowlisted;
    private static Deployment notAllowlisted;
    private static BigInteger masumiFloorLovelace;

    /** A committed deployment: the {@code extra.deployment} block and the hash/address recorded for it. */
    record Deployment(Map<String, Object> extra, String scriptHash, String escrowAddress) {
        MasumiBlueprint.MasumiDeployment params() {
            @SuppressWarnings("unchecked") List<String> keys = (List<String>) extra.get("adminVkeys");
            return new MasumiBlueprint.MasumiDeployment(new BigInteger((String) extra.get("requiredAdmins")),
                    keys, new BigInteger((String) extra.get("cooldownPeriod")));
        }
    }

    @BeforeAll
    static void stack() throws IOException {
        kit = DevnetKit.fromEnv();
        payments = new PaymentFactory(kit);
        facilitator = FacilitatorClient.defaultInstance();
        shim = ShimClient.fromEnv();
        JsonNode file = FacilitatorClient.JSON.readTree(Files.readString(deploymentFile()));
        allowlisted = deployment(file.get("allowlisted"));
        notAllowlisted = deployment(file.get("notAllowlisted"));
    }

    private static Path deploymentFile() {
        String fromEnv = System.getenv("E2E_MASUMI_DEPLOYMENT");
        return fromEnv != null && !fromEnv.isBlank() ? Path.of(fromEnv)
                : Path.of(System.getProperty("user.dir"), "e2e/devnet/masumi-deployment.json");
    }

    @SuppressWarnings("unchecked")
    private static Deployment deployment(JsonNode node) {
        return new Deployment(FacilitatorClient.JSON.convertValue(node.get("deployment"), Map.class),
                node.get("scriptHash").asText(), node.get("escrowAddress").asText());
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
        Wallet payer = kit.newWallet(30);
        Built built = new Lock().build(payer);
        assertVerifiedAndSettled(built, "lovelace");
    }

    @Test
    void aTokenLockIsSettledAndItsDatumIsOnChain() {
        Wallet payer = kit.newWallet(40);
        Lock lock = new Lock().token();
        Built built = lock.build(payer);
        assertThat(built.payment().requirements().get("asset")).isEqualTo(MINTED.get(payer.address()));
        assertVerifiedAndSettled(built, MINTED.get(payer.address()));
    }

    // ---- rejections ----

    /** One rejection: the baseline (control), and the single defect applied on top of it. */
    record Case(String name, String code, Consumer<Lock> baseline, Consumer<Lock> defect, boolean loneSurrogate) {
        Case(String name, String code, Consumer<Lock> defect) {
            this(name, code, k -> { }, defect, false);
        }

        Case(String name, String code, Consumer<Lock> baseline, Consumer<Lock> defect) {
            this(name, code, baseline, defect, false);
        }

        @Override
        public String toString() {
            return code + " <- " + name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("an unknown field in extra", "invalid_exact_cardano_requirements_masumi_schema",
                        k -> k.postSign = extra -> extra.put("unexpected", "field")),
                new Case("a commitment part whose content does not hash to its digest",
                        "invalid_exact_cardano_requirements_masumi_commitment",
                        k -> k.preSign = extra -> {
                            part(extra).put("digest", "ab".repeat(32));
                            recommit(extra);
                        }),
                new Case("a commitment digest that is not the manifest's hash",
                        "invalid_exact_cardano_requirements_masumi_commitment",
                        k -> k.preSign = extra -> {
                            commitment(extra).put("digest", "cd".repeat(32));
                            terms(extra).put("inputHash", "cd".repeat(32));
                        }),
                new Case("no deployment on a network without a canonical one",
                        "invalid_exact_cardano_requirements_masumi_deployment",
                        k -> k.postSign = extra -> extra.remove("deployment")),
                new Case("a deployment that does not derive to payTo",
                        "invalid_exact_cardano_requirements_masumi_deployment",
                        k -> k.postSign = extra -> extra.put("deployment", notAllowlisted.extra())),
                new Case("a signature over other terms", "invalid_exact_cardano_requirements_masumi_seller_signature",
                        k -> k.postSign = extra -> extra.put("referenceSignature", SELLER.signTermsHex("00".repeat(32)))),
                new Case("terms signed by a key that is not the seller",
                        "invalid_exact_cardano_requirements_masumi_seller_signature",
                        k -> k.signer = STRANGER),
                new Case("a valid deployment the facilitator does not allowlist",
                        "invalid_exact_cardano_payload_masumi_contract_mismatch",
                        k -> k.deployment = notAllowlisted),
                // The container has no registry validator, so ANY non-empty agent is refused.
                new Case("any agent identifier (no registry validator configured)",
                        "invalid_exact_cardano_requirements_masumi_agent_identifier",
                        k -> k.agent = "cd".repeat(28)),
                new Case("an identifier carrying another buyer nonce",
                        "invalid_exact_cardano_requirements_masumi_identifier",
                        k -> k.postSign = extra -> extra.put("blockchainIdentifier",
                                identifier(extra, k.escrow(), "ffffffffffffffff"))),
                new Case("two outputs to the escrow", "invalid_exact_cardano_payload_masumi_escrow_output_count",
                        k -> k.txEdit = MasumiTransferE2ETest::duplicateEscrowOutput),
                new Case("no inline datum", "invalid_exact_cardano_payload_masumi_datum_missing",
                        k -> k.noDatum = true),
                new Case("a reference script on the escrow output", "invalid_exact_cardano_payload_masumi_reference_script",
                        k -> k.txEdit = MasumiTransferE2ETest::addReferenceScript),
                new Case("a datum that is not a fresh FundsLocked lock", "invalid_exact_cardano_payload_masumi_datum_invalid",
                        k -> k.datumEdit = spec -> spec.withStateAlt(1L)),
                new Case("the lock is paid after payByTime", "invalid_exact_cardano_payload_masumi_deadline",
                        k -> k.payByFromNowMs = -10 * MINUTE_MS),
                new Case("deadlines closer together than the minimum spacing", "invalid_exact_cardano_payload_masumi_deadline",
                        k -> k.submitGapMs = MINUTE_MS),
                new Case("a datum with another seller nonce than the terms", "invalid_exact_cardano_payload_masumi_datum_mismatch",
                        k -> k.datumSellerNonce = "11".repeat(32)),
                new Case("a datum whose payByTime differs from the terms", "invalid_exact_cardano_payload_masumi_datum_mismatch",
                        k -> k.datumEdit = spec -> spec.withPayByTime(spec.payByTime().add(BigInteger.ONE))),
                // The output still carries amount + collateral, so ONLY the minimum rule is broken.
                new Case("a positive collateral below the minimum", "invalid_exact_cardano_payload_masumi_collateral",
                        k -> { k.collateral = 1_000_000L; k.sent = k.amount + k.collateral; }),
                new Case("an output that is not amount plus collateral", "invalid_exact_cardano_payload_masumi_collateral",
                        k -> k.collateral = 2_000_000L),
                new Case("more tokens locked than required", "invalid_exact_cardano_payload_masumi_asset",
                        Lock::token, k -> k.sent = 150L),
                new Case("a lock below the post-result min-UTxO floor", "invalid_exact_cardano_payload_masumi_min_utxo",
                        k -> k.amount = masumiFloor().longValueExact() - 50_000L),
                new Case("a malformed manifest string", "invalid_exact_cardano_payload_verification_error",
                        k -> { }, k -> k.preSign = extra -> {
                            Map<String, Object> part = part(extra);
                            part.remove("content");
                            part.put("name", LONE_SURROGATE);
                        }, true)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void rejects(Case c) {
        Wallet payer = kit.newWallet(40);

        // 1. control: the baseline alone is valid
        Lock baseline = new Lock();
        c.baseline().accept(baseline);
        FacilitatorClient.Response controlled = facilitator.verify(requestOf(c, baseline.build(payer).payment()));
        assertThat(controlled.json().path("isValid").asBoolean()).as("control for %s: %s", c, controlled).isTrue();

        // 2. exactly one defect -> exactly this code on /verify
        Lock defective = new Lock();
        c.baseline().accept(defective);
        c.defect().accept(defective);
        Payment payment = defective.build(payer).payment();
        Object request = requestOf(c, payment);
        FacilitatorClient.Response verify = facilitator.verify(request);
        assertThat(verify.status()).as("%s", verify).isEqualTo(200);
        JsonNode verified = SchemaAssert.assertMatches(SchemaAssert.VERIFY, verify);
        assertThat(verified.path("isValid").asBoolean()).as("%s", verify).isFalse();
        assertThat(verified.path("invalidReason").asText()).as("%s", verify).isEqualTo(c.code());

        // 3. the same payload on /settle: same code, nothing submitted
        FacilitatorClient.Response settle = facilitator.settle(request);
        assertThat(settle.status()).as("%s", settle).isEqualTo(200);
        JsonNode settled = SchemaAssert.assertMatches(SchemaAssert.SETTLE, settle);
        assertThat(settled.path("success").asBoolean()).as("%s", settle).isFalse();
        assertThat(settled.path("errorReason").asText()).as("%s", settle).isEqualTo(c.code());
        assertThat(settled.path("transaction").asText()).isEmpty();
        assertThat(shim.submitCount(payment.txBytes())).isZero();
    }

    private static Object requestOf(Case c, Payment payment) {
        Map<String, Object> body = payment.body();
        return c.loneSurrogate() ? FacilitatorClient.toJson(body).replace(LONE_SURROGATE, "\\ud800") : body;
    }

    // ---- the vector builder ----

    /** A built lock: the payment, the datum on its escrow output, and the extra that authorizes it. */
    record Built(Payment payment, PlutusData datum, Map<String, Object> extra) {
    }

    /**
     * Everything that can vary in a lock; the defaults are a valid 5 ADA lock to the
     * allowlisted deployment. A case changes at most the knobs of its one defect.
     */
    static final class Lock {
        Deployment deployment = allowlisted;
        MasumiTestSeller signer = SELLER;
        boolean token;
        long amount = 5_000_000L;
        Long sent;
        long collateral;
        long payByFromNowMs = 30 * MINUTE_MS;
        long submitGapMs = 10 * MINUTE_MS;
        long unlockGapMs = 20 * MINUTE_MS;
        long disputeGapMs = 20 * MINUTE_MS;
        String agent = "";
        String datumSellerNonce;
        boolean noDatum;
        Consumer<Map<String, Object>> preSign = extra -> { };
        Consumer<Map<String, Object>> postSign = extra -> { };
        java.util.function.UnaryOperator<TestTx.MasumiSpec> datumEdit = spec -> spec;
        BiFunction<Payment, Wallet, Payment> txEdit = (payment, payer) -> payment;

        /** A native-token lock: the output's ADA is exactly the datum's collateral. */
        Lock token() {
            this.token = true;
            this.amount = 100L;
            this.collateral = 4_000_000L;
            return this;
        }

        String escrow() {
            return deployment.escrowAddress();
        }

        Built build(Wallet payer) {
            String asset = token ? MINTED.computeIfAbsent(payer.address(), a -> kit.mintToken(payer, "E2EESCROW", 1_000)) : "lovelace";
            long now = System.currentTimeMillis();
            BigInteger payBy = BigInteger.valueOf(now + payByFromNowMs);
            BigInteger submit = payBy.add(BigInteger.valueOf(submitGapMs));
            BigInteger unlock = submit.add(BigInteger.valueOf(unlockGapMs));
            BigInteger dispute = unlock.add(BigInteger.valueOf(disputeGapMs));
            String sellerNonce = "8877665544332211".repeat(4);
            String buyerNonce = "1122334455667788";

            Map<String, Object> part = new LinkedHashMap<>();
            part.put("name", "request");
            part.put("canonicalization", "jcs");
            part.put("mediaType", "application/json");
            part.put("content", new LinkedHashMap<>(Map.of("prompt", "hello devnet")));
            part.put("digest", MasumiDigests.commitmentPartDigest(part));
            Map<String, Object> commitment = new LinkedHashMap<>();
            commitment.put("version", "1");
            commitment.put("algorithm", "sha256");
            commitment.put("parts", new ArrayList<>(List.of(part)));
            commitment.put("digest", MasumiDigests.computeInputHash(commitment));

            Map<String, Object> terms = new LinkedHashMap<>();
            terms.put("version", "1");
            terms.put("paymentType", "Web3CardanoV2");
            terms.put("sellerAddress", SELLER.sellerAddress);
            terms.put("sellerNonce", sellerNonce);
            terms.put("buyerNonce", buyerNonce);
            terms.put("agentIdentifier", agent);
            terms.put("inputHash", commitment.get("digest"));
            terms.put("payByTime", payBy.toString());
            terms.put("submitResultTime", submit.toString());
            terms.put("unlockTime", unlock.toString());
            terms.put("externalDisputeUnlockTime", dispute.toString());

            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("assetTransferMethod", "masumi");
            extra.put("inputCommitment", commitment);
            extra.put("terms", terms);
            extra.put("deployment", deployment.extra());
            preSign.accept(extra);

            String escrow = escrow();
            PaymentRequirements requirements = new PaymentRequirements("exact", DevnetKit.NETWORK, asset,
                    Long.toString(amount), escrow, PaymentFactory.DEFAULT_MAX_TIMEOUT_SECONDS, extra);
            String digest = MasumiDigests.computeTermsDigest(MasumiDigests.buildSignedTerms(extra, requirements));
            extra.put("referenceKey", signer.referenceKeyHex());
            extra.put("referenceSignature", signer.signTermsHex(digest));
            extra.put("blockchainIdentifier", identifier(extra, escrow, null));

            // The datum carries what the (valid) authorization says, whatever a postSign defect then does to extra.
            Map<String, Object> finalTerms = terms(extra);
            TestTx.MasumiSpec spec = new TestTx.MasumiSpec(BigInteger.valueOf(amount), payer.address(), false,
                    SELLER.sellerAddress, false, 0L, 19, (String) extra.get("referenceKey"),
                    (String) extra.get("referenceSignature"),
                    datumSellerNonce != null ? datumSellerNonce : (String) finalTerms.get("sellerNonce"),
                    (String) finalTerms.get("buyerNonce"), (String) finalTerms.get("agentIdentifier"),
                    BigInteger.valueOf(collateral), (String) finalTerms.get("inputHash"), "",
                    new BigInteger((String) finalTerms.get("payByTime")),
                    new BigInteger((String) finalTerms.get("submitResultTime")),
                    new BigInteger((String) finalTerms.get("unlockTime")),
                    new BigInteger((String) finalTerms.get("externalDisputeUnlockTime")),
                    0L, false, null, null);
            PlutusData datum = TestTx.buildMasumiDatum(datumEdit.apply(spec));
            postSign.accept(extra);

            PaymentFactory.Spec builder = payments.from(payer).payTo(escrow);
            if (token) builder.token(asset, amount);
            else builder.lovelace(amount);
            if (sent != null) builder.send(sent);
            if (!noDatum) builder.inlineDatum(datum);
            Payment payment = builder.build().withRequirement("extra", extra);
            if (token) payment = setPaidCoin(payment, payer, escrow, collateral);
            return new Built(txEdit.apply(payment, payer), datum, extra);
        }
    }

    // ---- extra surgery ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> terms(Map<String, Object> extra) {
        return (Map<String, Object>) extra.get("terms");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> commitment(Map<String, Object> extra) {
        return (Map<String, Object>) extra.get("inputCommitment");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> part(Map<String, Object> extra) {
        return ((List<Map<String, Object>>) commitment(extra).get("parts")).get(0);
    }

    /** Re-derives the commitment digest from the (edited) parts, and the terms' inputHash with it. */
    private static void recommit(Map<String, Object> extra) {
        String digest = MasumiDigests.computeInputHash(commitment(extra));
        commitment(extra).put("digest", digest);
        terms(extra).put("inputHash", digest);
    }

    private static String identifier(Map<String, Object> extra, String payTo, String buyerNonceOverride) {
        Map<String, Object> terms = terms(extra);
        return MasumiTestSeller.encodeIdentifier(MasumiIdentifier.buildIdentifierText(
                new MasumiIdentifier.IdentifierParts((String) terms.get("sellerNonce"), (String) terms.get("agentIdentifier"),
                        buyerNonceOverride != null ? buyerNonceOverride : (String) terms.get("buyerNonce"),
                        (String) extra.get("referenceSignature"), (String) extra.get("referenceKey"), payTo)));
    }

    // ---- transaction surgery (re-signed by the payer; fee headroom is moved out of the change output) ----

    private static final long FEE_HEADROOM = 30_000L;

    /** Sets the escrow output's ADA exactly, moving the difference to/from the change output. */
    private static Payment setPaidCoin(Payment payment, Wallet payer, String escrow, long coin) {
        return payments.mutateAndResign(payment, tx -> {
            TransactionOutput paid = outputTo(tx, escrow);
            BigInteger delta = paid.getValue().getCoin().subtract(BigInteger.valueOf(coin));
            paid.getValue().setCoin(BigInteger.valueOf(coin));
            TransactionOutput change = outputTo(tx, payer.address());
            change.getValue().setCoin(change.getValue().getCoin().add(delta));
        });
    }

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

    private static TransactionOutput outputTo(Transaction tx, String address) {
        return tx.getBody().getOutputs().stream().filter(o -> o.getAddress().equals(address)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no output to " + address));
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

    private static void assertVerifiedAndSettled(Built built, String asset) {
        Payment payment = built.payment();
        FacilitatorClient.Response verify = facilitator.verify(payment.body());
        assertThat(verify.status()).as("%s", verify).isEqualTo(200);
        JsonNode verified = SchemaAssert.assertMatches(SchemaAssert.VERIFY, verify);
        assertThat(verified.path("isValid").asBoolean()).as("%s", verify).isTrue();
        assertThat(verified.path("payer").asText()).isEqualTo(payment.payer().address());

        FacilitatorClient.Response settle = facilitator.settle(payment.body());
        assertThat(settle.status()).as("%s", settle).isEqualTo(200);
        JsonNode settled = SchemaAssert.assertMatches(SchemaAssert.SETTLE, settle);
        assertThat(settled.path("success").asBoolean()).as("%s", settle).isTrue();
        assertThat(settled.path("transaction").asText()).isEqualTo(payment.txHash());
        assertThat(settled.path("payer").asText()).isEqualTo(payment.payer().address());
        assertThat(settled.path("extra").path("status").asText()).isEqualTo("confirmed");

        kit.assertOnChain(payment.txHash());
        assertThat(shim.submitCount(payment.txBytes())).isEqualTo(1);
        assertEscrowUtxoOnChain(built, asset);
    }

    /** Reads the escrow UTxO back from Yano directly and compares its inline datum with the one that was built. */
    private static void assertEscrowUtxoOnChain(Built built, String asset) {
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
