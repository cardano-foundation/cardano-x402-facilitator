package org.cardanofoundation.x402.facilitator.e2e.kit;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.fasterxml.jackson.databind.JsonNode;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiBlueprint;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiDigests;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiIdentifier;
import org.cardanofoundation.x402.facilitator.testutil.MasumiTestSeller;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;

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
import java.util.function.UnaryOperator;

/**
 * Everything that can vary in a Masumi escrow lock; the defaults are a valid 5 ADA
 * lock to the allowlisted deployment. A case changes at most the knobs of its one
 * defect. Public fields: a case sets them from a test class in another package.
 *
 * <p>Oracle note: main code ({@code MasumiDigests}, {@code MasumiBlueprint},
 * {@code MasumiIdentifier}) and the unit suite's {@code MasumiTestSeller}/{@code TestTx}
 * are the VECTOR BUILDER here, as documented on MasumiTransferE2ETest.
 */
public final class MasumiLock {

    public static final long MINUTE_MS = 60_000L;
    public static final MasumiTestSeller SELLER = new MasumiTestSeller("33");
    public static final MasumiTestSeller STRANGER = new MasumiTestSeller("44");

    /** One token minted per payer wallet, shared by every build from that wallet. */
    private static final Map<String, String> MINTED = new ConcurrentHashMap<>();

    /** A committed deployment: the {@code extra.deployment} block and the hash/address recorded for it. */
    public record Deployment(Map<String, Object> extra, String scriptHash, String escrowAddress) {
        public MasumiBlueprint.MasumiDeployment params() {
            @SuppressWarnings("unchecked") List<String> keys = (List<String>) extra.get("adminVkeys");
            return new MasumiBlueprint.MasumiDeployment(new BigInteger((String) extra.get("requiredAdmins")),
                    keys, new BigInteger((String) extra.get("cooldownPeriod")));
        }
    }

    /** The two committed deployments of {@code e2e/devnet/masumi-deployment.json}. */
    public record Deployments(Deployment allowlisted, Deployment notAllowlisted) {
    }

    /** Reads the committed deployments (file from {@code E2E_MASUMI_DEPLOYMENT}, else the repo's). */
    public static Deployments deployments() {
        try {
            JsonNode file = FacilitatorClient.JSON.readTree(Files.readString(deploymentFile()));
            return new Deployments(deployment(file.get("allowlisted")), deployment(file.get("notAllowlisted")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the Masumi deployment file", e);
        }
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

    /** A built lock: the payment, the datum on its escrow output, and the extra that authorizes it. */
    public record Built(Payment payment, PlutusData datum, Map<String, Object> extra) {
    }

    private final DevnetKit kit;
    private final PaymentFactory payments;

    public Deployment deployment;
    public MasumiTestSeller signer = SELLER;
    public boolean token;
    public long amount = 5_000_000L;
    public Long sent;
    public long collateral;
    public long payByFromNowMs = 30 * MINUTE_MS;
    public long submitGapMs = 10 * MINUTE_MS;
    public long unlockGapMs = 20 * MINUTE_MS;
    public long disputeGapMs = 20 * MINUTE_MS;
    public String agent = "";
    public String datumSellerNonce;
    public boolean noDatum;
    public Consumer<Map<String, Object>> preSign = extra -> { };
    public Consumer<Map<String, Object>> postSign = extra -> { };
    public UnaryOperator<TestTx.MasumiSpec> datumEdit = spec -> spec;
    public BiFunction<Payment, Wallet, Payment> txEdit = (payment, payer) -> payment;

    /** @param deployment the deployment the lock pays to unless a case replaces it. */
    public MasumiLock(DevnetKit kit, PaymentFactory payments, Deployment deployment) {
        this.kit = kit;
        this.payments = payments;
        this.deployment = deployment;
    }

    /** A native-token lock: the output's ADA is exactly the datum's collateral. */
    public MasumiLock token() {
        this.token = true;
        this.amount = 100L;
        this.collateral = 4_000_000L;
        return this;
    }

    public String escrow() {
        return deployment.escrowAddress();
    }

    /** The asset minted to the payer for a token lock. */
    public static String mintedFor(String payerAddress) {
        return MINTED.get(payerAddress);
    }

    public Built build(Wallet payer) {
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

    // ---- extra surgery ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> terms(Map<String, Object> extra) {
        return (Map<String, Object>) extra.get("terms");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> commitment(Map<String, Object> extra) {
        return (Map<String, Object>) extra.get("inputCommitment");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> part(Map<String, Object> extra) {
        return ((List<Map<String, Object>>) commitment(extra).get("parts")).get(0);
    }

    /** Re-derives the commitment digest from the (edited) parts, and the terms' inputHash with it. */
    public static void recommit(Map<String, Object> extra) {
        String digest = MasumiDigests.computeInputHash(commitment(extra));
        commitment(extra).put("digest", digest);
        terms(extra).put("inputHash", digest);
    }

    public static String identifier(Map<String, Object> extra, String payTo, String buyerNonceOverride) {
        Map<String, Object> terms = terms(extra);
        return MasumiTestSeller.encodeIdentifier(MasumiIdentifier.buildIdentifierText(
                new MasumiIdentifier.IdentifierParts((String) terms.get("sellerNonce"), (String) terms.get("agentIdentifier"),
                        buyerNonceOverride != null ? buyerNonceOverride : (String) terms.get("buyerNonce"),
                        (String) extra.get("referenceSignature"), (String) extra.get("referenceKey"), payTo)));
    }

    // ---- transaction surgery (re-signed by the payer) ----

    /** Sets the escrow output's ADA exactly, moving the difference to/from the change output. */
    private Payment setPaidCoin(Payment payment, Wallet payer, String escrow, long coin) {
        return payments.mutateAndResign(payment, tx -> {
            TransactionOutput paid = outputTo(tx, escrow);
            BigInteger delta = paid.getValue().getCoin().subtract(BigInteger.valueOf(coin));
            paid.getValue().setCoin(BigInteger.valueOf(coin));
            TransactionOutput change = outputTo(tx, payer.address());
            change.getValue().setCoin(change.getValue().getCoin().add(delta));
        });
    }

    /** The first output of the transaction to {@code address}. */
    public static TransactionOutput outputTo(Transaction tx, String address) {
        return tx.getBody().getOutputs().stream().filter(o -> o.getAddress().equals(address)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no output to " + address));
    }
}
