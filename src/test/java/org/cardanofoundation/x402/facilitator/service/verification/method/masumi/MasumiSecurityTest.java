package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.testutil.MasumiTestSeller;
import org.cardanofoundation.x402.facilitator.testutil.TestTx;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiTransferVerifierTest.*;

class MasumiSecurityTest {
    static final String ESCROW = MasumiBlueprint.escrowAddress("cardano:preprod", MasumiBlueprint.DEFAULT_DEPLOYMENT);
    static final String TOKEN = "ab".repeat(28) + ".01";
    static final String REGISTRY = "67ab0c92c4ac1610895a1c965ee50aba41a8f1513b15240723b3bd0b";

    static DecodedTransaction lock(Map<String, Object> extra, TestTx.MasumiSpec spec) {
        return new CardanoTransactionDecoder().decode(TestTx.buildMasumiLockBase64(ESCROW, signedSpec(spec, extra)));
    }

    static DecodedTransaction withOutputs(DecodedTransaction tx, List<DecodedTransaction.Output> outputs, Set<String> witnesses) {
        return new DecodedTransaction(tx.txHashHex(), tx.inputs(), outputs, tx.ttlSlot(), tx.validityStartSlot(),
                tx.networkId(), tx.vkeyWitnessCount(), tx.scriptWitnessCount(), tx.signaturesValid(), witnesses, tx.serializedSize());
    }

    static DecodedTransaction value(DecodedTransaction tx, BigInteger coin, Map<String, BigInteger> assets) {
        var output = tx.outputs().getFirst();
        return withOutputs(tx, List.of(new DecodedTransaction.Output(output.address(), coin, assets,
                output.raw(), output.inlineDatumRawLen())), tx.verifiedWitnessKeyHashes());
    }

    @Test void completeQuoteWithNullContentIsAccepted() {
        var extra = defaultExtra();
        assertThat(new MasumiTransferVerifier().check(extra, requirements(extra), lock(extra, TestTx.MasumiSpec.defaults()),
                TestTx.PAYER_ADDRESS, null)).isEmpty();
        assertThat(MasumiIdentifier.decode((String) extra.get("blockchainIdentifier"))).isNotNull();
    }

    @Test void rejectsTamperingOfEachIdentifierComponent() {
        for (int index = 0; index < 6; index++) {
            var extra = defaultExtra();
            var decoded = MasumiIdentifier.decode((String) extra.get("blockchainIdentifier"));
            String[] parts = {decoded.sellerNonce(), decoded.agentIdentifier(), decoded.buyerNonce(),
                    decoded.referenceSignature(), decoded.referenceKey(), decoded.contractAddress()};
            parts[index] = index == 5 ? TestTx.SELLER_ADDRESS : "ff" + parts[index].substring(Math.min(parts[index].length(), 2));
            extra.put("blockchainIdentifier", MasumiTestSeller.encodeIdentifier(MasumiIdentifier.buildIdentifierText(
                    new MasumiIdentifier.IdentifierParts(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]))));
            assertThat(new MasumiTransferVerifier().check(extra, requirements(extra), lock(extra, TestTx.MasumiSpec.defaults()),
                    TestTx.PAYER_ADDRESS, null)).as("component %s", index).contains(ErrorCodes.MASUMI_IDENTIFIER);
        }
    }

    @Test void hashesExplicitJcsNullButAllowsOmittedContent() {
        var extra = defaultExtra();
        var commitment = MasumiSchemaTest.object(extra, "inputCommitment");
        Map<String, Object> part = new LinkedHashMap<>(Map.of("name", "request", "canonicalization", "jcs",
                "digest", "ab".repeat(32)));
        commitment.put("parts", List.of(part));
        commitment.put("digest", MasumiDigests.computeInputHash(commitment));
        MasumiSchemaTest.object(extra, "terms").put("inputHash", commitment.get("digest"));
        resign(extra, "lovelace", TestTx.MASUMI_AMOUNT.toString(), ESCROW);
        var tx = lock(extra, TestTx.MasumiSpec.defaults());
        var datum = (ConstrPlutusData) tx.outputs().getFirst().raw().getInlineDatum();
        datum.getData().getPlutusDataList().set(10, BytesPlutusData.of(
                com.bloxbean.cardano.client.util.HexUtil.decodeHexString((String) commitment.get("digest"))));
        assertThat(new MasumiTransferVerifier().check(extra, requirements(extra), tx, TestTx.PAYER_ADDRESS, null)).isEmpty();
        part.put("content", null);
        assertThat(new MasumiTransferVerifier().check(extra, requirements(extra), tx, TestTx.PAYER_ADDRESS, null))
                .contains(ErrorCodes.MASUMI_COMMITMENT);
    }

    @Test void rejectsMultipleEscrowOutputsEvenIfOnlyOneHasADatum() {
        var extra = defaultExtra();
        var tx = lock(extra, TestTx.MasumiSpec.defaults());
        var output = tx.outputs().getFirst();
        for (var second : List.of(output, new DecodedTransaction.Output(output.address(), output.coin(), Map.of(),
                tx.outputs().get(1).raw(), 0))) {
            var outputs = new ArrayList<>(tx.outputs()); outputs.add(second);
            assertThat(new MasumiTransferVerifier().check(extra, requirements(extra), withOutputs(tx, outputs, tx.verifiedWitnessKeyHashes()),
                    TestTx.PAYER_ADDRESS, null)).contains(ErrorCodes.MASUMI_ESCROW_OUTPUT_COUNT);
        }
    }

    @Test void requiresExactAdaIncludingStructuralCollateral() {
        var extra = signedExtra(defaultTerms(), "lovelace", "1500000", ESCROW);
        var req = new PaymentRequirements("exact", "cardano:preprod", "lovelace", "1500000", ESCROW, 600, extra);
        var tx = lock(extra, TestTx.MasumiSpec.defaults().withCollateralReturnLovelace(BigInteger.valueOf(1_435_230)));
        var verifier = new MasumiTransferVerifier();
        assertThat(verifier.check(extra, req, value(tx, BigInteger.valueOf(2_935_230), Map.of()), TestTx.PAYER_ADDRESS, null)).isEmpty();
        for (long coin : List.of(2_935_229L, 2_935_231L)) {
            assertThat(verifier.check(extra, req, value(tx, BigInteger.valueOf(coin), Map.of()), TestTx.PAYER_ADDRESS, null))
                    .contains(ErrorCodes.MASUMI_COLLATERAL);
        }
    }

    @Test void nativeTokenRequiresExactCoinQuantityAndAssetSet() {
        var extra = signedExtra(defaultTerms(), TOKEN, "7", ESCROW);
        var req = new PaymentRequirements("exact", "cardano:preprod", TOKEN, "7", ESCROW, 600, extra);
        var tx = lock(extra, TestTx.MasumiSpec.defaults().withCollateralReturnLovelace(BigInteger.valueOf(3_000_000)));
        var verifier = new MasumiTransferVerifier();
        assertThat(verifier.check(extra, req, value(tx, BigInteger.valueOf(3_000_000), Map.of(TOKEN, BigInteger.valueOf(7))),
                TestTx.PAYER_ADDRESS, null)).isEmpty();
        assertThat(verifier.check(extra, req, value(tx, BigInteger.valueOf(3_000_001), Map.of(TOKEN, BigInteger.valueOf(7))),
                TestTx.PAYER_ADDRESS, null)).contains(ErrorCodes.MASUMI_COLLATERAL);
        for (var assets : List.of(Map.of(TOKEN, BigInteger.valueOf(8)),
                Map.of(TOKEN, BigInteger.valueOf(7), "cd".repeat(28) + ".01", BigInteger.ONE))) {
            assertThat(verifier.check(extra, req, value(tx, BigInteger.valueOf(3_000_000), assets),
                    TestTx.PAYER_ADDRESS, null)).contains(ErrorCodes.MASUMI_ASSET);
        }
    }

    @Test void buyerMayChooseADifferentStakeCredentialButMustWitnessPaymentKey() {
        var extra = defaultExtra();
        var payment = new Address(TestTx.PAYER_ADDRESS).getPaymentCredential().orElseThrow();
        String base = AddressProvider.getBaseAddress(payment, Credential.fromKey(new byte[28]), Networks.testnet()).toBech32();
        var tx = lock(extra, TestTx.MasumiSpec.defaults().withBuyerAddress(base));
        assertThat(new MasumiTransferVerifier().check(extra, requirements(extra), tx, TestTx.PAYER_ADDRESS, null)).isEmpty();
        assertThat(new MasumiTransferVerifier().check(extra, requirements(extra), withOutputs(tx, tx.outputs(), Set.of()),
                TestTx.PAYER_ADDRESS, null)).contains(ErrorCodes.MASUMI_DATUM_MISMATCH);
    }

    @Test void rejectsRegistryClaimsWithoutIndependentValidator() {
        var extra = extraWithTerm("agentIdentifier", REGISTRY + "01");
        var tx = lock(extra, TestTx.MasumiSpec.defaults());
        var datum = (ConstrPlutusData) tx.outputs().getFirst().raw().getInlineDatum();
        datum.getData().getPlutusDataList().set(8, BytesPlutusData.of(
                com.bloxbean.cardano.client.util.HexUtil.decodeHexString(REGISTRY + "01")));
        assertThat(new MasumiTransferVerifier().check(extra, requirements(extra), tx, TestTx.PAYER_ADDRESS, null))
                .contains(ErrorCodes.MASUMI_AGENT_IDENTIFIER);
    }

    @Test void explicitDeploymentNeedsApprovalEvenIfItRestatesCanonicalParameters() {
        var extra = defaultExtra(); var deployment = MasumiBlueprint.DEFAULT_DEPLOYMENT;
        extra.put("deployment", Map.of("requiredAdmins", deployment.requiredAdmins().toString(),
                "adminVkeys", deployment.adminVkeys(), "cooldownPeriod", deployment.cooldownPeriod().toString()));
        var tx = lock(extra, TestTx.MasumiSpec.defaults());
        assertThat(new MasumiTransferVerifier().check(extra, requirements(extra), tx, TestTx.PAYER_ADDRESS, null))
                .contains(ErrorCodes.MASUMI_DEPLOYMENT);
        var allowed = Map.of("cardano:preprod", Set.of(MasumiBlueprint.escrowScriptHash(deployment)));
        assertThat(new MasumiTransferVerifier(Map.of(), allowed).check(extra, requirements(extra), tx,
                TestTx.PAYER_ADDRESS, null)).isEmpty();
    }
    @Test void registryValidatorReceivesResourceTermsRequirementsAndNetworkAfterSignature() {
        var extra = extraWithTerm("agentIdentifier", REGISTRY + "01");
        var req = requirements(extra);
        var tx = lock(extra, TestTx.MasumiSpec.defaults());
        var datum = (ConstrPlutusData) tx.outputs().getFirst().raw().getInlineDatum();
        datum.getData().getPlutusDataList().set(8, BytesPlutusData.of(
                com.bloxbean.cardano.client.util.HexUtil.decodeHexString(REGISTRY + "01")));
        Map<String, Object> resource = Map.of("url", "https://seller.example/run");
        var seen = new java.util.concurrent.atomic.AtomicReference<MasumiRegistryValidator.Context>();
        var verifier = new MasumiTransferVerifier(Map.of(), Map.of(), context -> { seen.set(context); return true; }, null);
        assertThat(verifier.check(extra, req, tx, TestTx.PAYER_ADDRESS, null, resource)).isEmpty();
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().requirements()).isEqualTo(req);
        assertThat(seen.get().terms()).isEqualTo(extra.get("terms"));
        assertThat(seen.get().resource()).isEqualTo(resource);
        assertThat(seen.get().network()).isEqualTo("cardano:preprod");
        assertThat(verifier.check(extra, req, tx, TestTx.PAYER_ADDRESS, null))
                .contains(ErrorCodes.MASUMI_AGENT_IDENTIFIER);
        var rejecting = new MasumiTransferVerifier(Map.of(), Map.of(), context -> false, null);
        assertThat(rejecting.check(extra, req, tx, TestTx.PAYER_ADDRESS, null, resource))
                .contains(ErrorCodes.MASUMI_AGENT_IDENTIFIER);
        var unavailable = new MasumiTransferVerifier(Map.of(), Map.of(), context -> { throw new IllegalStateException(); }, null);
        assertThat(unavailable.check(extra, req, tx, TestTx.PAYER_ADDRESS, null, resource))
                .contains(ErrorCodes.MASUMI_AGENT_IDENTIFIER);
        seen.set(null);
        extra.put("referenceSignature", "00");
        assertThat(verifier.check(extra, req, tx, TestTx.PAYER_ADDRESS, null, resource))
                .contains(ErrorCodes.MASUMI_AUTHORIZATION);
        assertThat(seen.get()).isNull();
    }

    @Test void deploymentValidatorExplicitlyApprovesTrustDomain() {
        var extra = defaultExtra(); var deployment = MasumiBlueprint.DEFAULT_DEPLOYMENT;
        extra.put("deployment", Map.of("requiredAdmins", deployment.requiredAdmins().toString(),
                "adminVkeys", deployment.adminVkeys(), "cooldownPeriod", deployment.cooldownPeriod().toString()));
        var tx = lock(extra, TestTx.MasumiSpec.defaults());
        var seen = new java.util.concurrent.atomic.AtomicReference<MasumiDeploymentValidator.Context>();
        var approved = new MasumiTransferVerifier(Map.of(), Map.of(), null, context -> { seen.set(context); return true; });
        assertThat(approved.check(extra, requirements(extra), tx, TestTx.PAYER_ADDRESS, null)).isEmpty();
        assertThat(seen.get()).isEqualTo(new MasumiDeploymentValidator.Context("cardano:preprod", ESCROW, deployment));
        var rejected = new MasumiTransferVerifier(Map.of(), Map.of(), null, context -> false);
        assertThat(rejected.check(extra, requirements(extra), tx, TestTx.PAYER_ADDRESS, null)).contains(ErrorCodes.MASUMI_DEPLOYMENT);
    }

    @Test void rejectsScriptStakeCredentialsInBuyerDatum() {
        var extra = defaultExtra();
        var payment = new Address(TestTx.PAYER_ADDRESS).getPaymentCredential().orElseThrow();
        String base = AddressProvider.getBaseAddress(payment, Credential.fromKey(new byte[28]), Networks.testnet()).toBech32();
        var tx = lock(extra, TestTx.MasumiSpec.defaults().withBuyerAddress(base));
        var root = (ConstrPlutusData) tx.outputs().getFirst().raw().getInlineDatum();
        var buyer = (ConstrPlutusData) root.getData().getPlutusDataList().get(0);
        var option = (ConstrPlutusData) buyer.getData().getPlutusDataList().get(1);
        var inline = (ConstrPlutusData) option.getData().getPlutusDataList().get(0);
        inline.getData().getPlutusDataList().set(0, ConstrPlutusData.of(1, BytesPlutusData.of(new byte[28])));
        assertThat(new MasumiTransferVerifier().check(extra, requirements(extra), tx, base, null))
                .contains(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsStateConstructorWithHiddenFields() {
        var extra = defaultExtra(); var tx = lock(extra, TestTx.MasumiSpec.defaults());
        var root = (ConstrPlutusData) tx.outputs().getFirst().raw().getInlineDatum();
        root.getData().getPlutusDataList().set(18, ConstrPlutusData.of(0, BytesPlutusData.of(new byte[0])));
        assertThat(new MasumiTransferVerifier().check(extra, requirements(extra), tx, TestTx.PAYER_ADDRESS, null))
                .contains(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void rejectsMalformedCredentialLengthInBuyerChosenReturnAddress() {
        var extra = defaultExtra(); var tx = lock(extra, TestTx.MasumiSpec.defaults().withBuyerReturnAddress(TestTx.PAYER_ADDRESS));
        var root = (ConstrPlutusData) tx.outputs().getFirst().raw().getInlineDatum();
        var option = (ConstrPlutusData) root.getData().getPlutusDataList().get(1);
        var address = (ConstrPlutusData) option.getData().getPlutusDataList().get(0);
        address.getData().getPlutusDataList().set(0, ConstrPlutusData.of(0, BytesPlutusData.of(new byte[27])));
        assertThat(new MasumiTransferVerifier().check(extra, requirements(extra), tx, TestTx.PAYER_ADDRESS, null))
                .contains(ErrorCodes.MASUMI_DATUM_INVALID);
    }

    @Test void requiresPostResultMinUtxoHeadroom() {
        var extra = signedExtra(defaultTerms(), "lovelace", "1500000", ESCROW);
        var req = new PaymentRequirements("exact", "cardano:preprod", "lovelace", "1500000", ESCROW, 600, extra);
        var tx = lock(extra, TestTx.MasumiSpec.defaults().withCollateralReturnLovelace(BigInteger.valueOf(1_435_230)));
        tx = value(tx, BigInteger.valueOf(2_935_230), Map.of());
        assertThat(new MasumiTransferVerifier().check(extra, req, tx, TestTx.PAYER_ADDRESS, BigInteger.valueOf(4310)))
                .contains(ErrorCodes.MASUMI_MIN_UTXO);
    }

}
