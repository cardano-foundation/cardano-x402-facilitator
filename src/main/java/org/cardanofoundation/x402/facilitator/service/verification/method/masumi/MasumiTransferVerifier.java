package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import org.cardanofoundation.x402.facilitator.chain.NetworkClock;
import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;
import org.cardanofoundation.x402.facilitator.service.verification.method.TransferMethodVerifier;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiDatum.MasumiAddressCredentials;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiDatum.MasumiDatumView;
import org.cardanofoundation.x402.facilitator.service.verification.method.script.ScriptAddress;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Validates the signed job authorization and the exact fresh Masumi V2 escrow lock. */
public class MasumiTransferVerifier implements TransferMethodVerifier {
    private static final String REGISTRY_POLICY = "67ab0c92c4ac1610895a1c965ee50aba41a8f1513b15240723b3bd0b";
    private final Map<String, NetworkClock> clocksByNetwork;
    private final Map<String, Set<String>> allowedScriptHashesByNetwork;
    private final MasumiRegistryValidator registryValidator;
    private final MasumiDeploymentValidator deploymentValidator;

    public MasumiTransferVerifier() { this(Map.of(), Map.of()); }

    public MasumiTransferVerifier(Map<String, NetworkClock> clocksByNetwork,
                                  Map<String, Set<String>> allowedScriptHashesByNetwork) {
        this(clocksByNetwork, allowedScriptHashesByNetwork, null, null);
    }

    public MasumiTransferVerifier(Map<String, NetworkClock> clocksByNetwork,
                                  Map<String, Set<String>> allowedScriptHashesByNetwork,
                                  MasumiRegistryValidator registryValidator,
                                  MasumiDeploymentValidator deploymentValidator) {
        this.clocksByNetwork = Map.copyOf(clocksByNetwork);
        Map<String, Set<String>> allowlists = new LinkedHashMap<>();
        allowedScriptHashesByNetwork.forEach((network, hashes) -> allowlists.put(CardanoNetworks.normalize(network), Set.copyOf(hashes)));
        this.allowedScriptHashesByNetwork = Map.copyOf(allowlists);
        this.registryValidator = registryValidator;
        this.deploymentValidator = deploymentValidator;
    }

    @Override public boolean supports(String method) { return "masumi".equals(method); }

    @Override
    public Optional<String> check(Map<String, Object> extra, PaymentRequirements requirements,
                                  DecodedTransaction tx, String payer, BigInteger coinsPerUtxoByte) {
        return check(extra, requirements, tx, payer, coinsPerUtxoByte, null);
    }

    @Override
    public Optional<String> check(Map<String, Object> extra, PaymentRequirements requirements,
                                  DecodedTransaction tx, String payer, BigInteger coinsPerUtxoByte,
                                  Map<String, Object> resource) {
        Optional<String> authorization = verifyAuthorization(extra, requirements, resource);
        if (authorization.isPresent()) return authorization;
        String escrowAddress = requirements.payTo();
        List<DecodedTransaction.Output> outputs = tx.outputs().stream().filter(o -> escrowAddress.equals(o.address())).toList();
        if (outputs.size() != 1) return Optional.of(ErrorCodes.MASUMI_ESCROW_OUTPUT_COUNT);
        var output = outputs.getFirst();
        if (output.raw().getInlineDatum() == null) return Optional.of(ErrorCodes.MASUMI_DATUM_MISSING);
        if (output.raw().getScriptRef() != null) return Optional.of(ErrorCodes.MASUMI_REFERENCE_SCRIPT);
        MasumiDatumView view = MasumiDatum.parse(output.raw().getInlineDatum());
        if (view == null || freshLockInvariantsViolated(view)) return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);

        var escrow = MasumiDatum.addressCredentials(escrowAddress);
        for (var address : new MasumiAddressCredentials[] { view.buyer(), view.seller(), view.buyerReturnAddress(), view.sellerReturnAddress() }) {
            if (address != null && MasumiDatum.sameCredentials(address, escrow)) return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);
        }
        var buyerTarget = view.buyerReturnAddress() != null ? view.buyerReturnAddress() : view.buyer();
        var sellerTarget = view.sellerReturnAddress() != null ? view.sellerReturnAddress() : view.seller();
        if (MasumiDatum.sameCredentials(buyerTarget, sellerTarget)) return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);

        if (tx.ttlSlot() == null || BigInteger.valueOf(clockFor(requirements.network()).slotToTime(tx.ttlSlot()).toEpochMilli())
                .compareTo(view.payByTime()) > 0) return Optional.of(ErrorCodes.MASUMI_DEADLINE);
        if (!view.buyer().payment().hash().equals(MasumiDatum.addressCredentials(payer).payment().hash())
                || !tx.verifiedWitnessKeyHashes().contains(view.buyer().payment().hash())) {
            return Optional.of(ErrorCodes.MASUMI_DATUM_MISMATCH);
        }
        if (fieldsMismatch(extra, view)) return Optional.of(ErrorCodes.MASUMI_DATUM_MISMATCH);

        BigInteger collateral = view.collateralReturnLovelace();
        if (collateral.signum() < 0 || collateral.signum() > 0 && collateral.compareTo(MasumiConstants.MASUMI_MIN_COLLATERAL_LOVELACE) < 0) {
            return Optional.of(ErrorCodes.MASUMI_COLLATERAL);
        }
        BigInteger amount = new BigInteger(requirements.amount());
        String asset = TransferMethodVerifier.assetKey(requirements);
        boolean lovelace = TransferMethodVerifier.isLovelace(asset);
        if (!output.coin().equals(collateral.add(lovelace ? amount : BigInteger.ZERO))) return Optional.of(ErrorCodes.MASUMI_COLLATERAL);
        if (!lovelace && !amount.equals(output.assets().get(asset)) || output.assets().size() != (lovelace ? 0 : 1)) {
            return Optional.of(ErrorCodes.MASUMI_ASSET);
        }
        if (coinsPerUtxoByte != null && output.coin().compareTo(MasumiConstants.masumiMinUtxoLovelace(
                output.inlineDatumRawLen(), output.assets().size(), coinsPerUtxoByte)) < 0) return Optional.of(ErrorCodes.MASUMI_MIN_UTXO);
        return Optional.empty();
    }

    private Optional<String> verifyAuthorization(Map<String, Object> extra, PaymentRequirements requirements,
                                                 Map<String, Object> resource) {
        if (MasumiSchema.validate(extra, requirements.network()).isPresent()) return Optional.of(ErrorCodes.MASUMI_SCHEMA);
        Map<String, Object> terms = object(extra, "terms");
        if (!deadlineIntervals(new BigInteger((String) terms.get("payByTime")), new BigInteger((String) terms.get("submitResultTime")),
                new BigInteger((String) terms.get("unlockTime")), new BigInteger((String) terms.get("externalDisputeUnlockTime")))) {
            return Optional.of(ErrorCodes.MASUMI_DEADLINE);
        }
        Map<String, Object> commitment = object(extra, "inputCommitment");
        try {
            for (Object value : (List<?>) commitment.get("parts")) {
                @SuppressWarnings("unchecked") Map<String, Object> part = (Map<String, Object>) value;
                // Null is JCS content. Only an absent property means the issuer withheld the content.
                if (part.containsKey("content") && !part.get("digest").equals(MasumiDigests.commitmentPartDigest(part))) {
                    return Optional.of(ErrorCodes.MASUMI_COMMITMENT);
                }
            }
        } catch (RuntimeException e) { return Optional.of(ErrorCodes.MASUMI_COMMITMENT); }
        // Match upstream's catch boundary: part content errors are commitment
        // failures; malformed manifest strings reach the scheme's generic
        // verification-error boundary rather than becoming a digest mismatch.
        if (!commitment.get("digest").equals(MasumiDigests.computeInputHash(commitment))) return Optional.of(ErrorCodes.MASUMI_COMMITMENT);

        var deployment = resolveDeployment(extra, requirements.network());
        if (deployment == null) return Optional.of(ErrorCodes.MASUMI_DEPLOYMENT);
        try {
            if (!MasumiBlueprint.escrowAddress(requirements.network(), deployment).equals(requirements.payTo())) {
                return Optional.of(ErrorCodes.MASUMI_DEPLOYMENT);
            }
        } catch (RuntimeException e) { return Optional.of(ErrorCodes.MASUMI_DEPLOYMENT); }
        String termsDigest = MasumiDigests.computeTermsDigest(MasumiDigests.buildSignedTerms(extra, requirements));
        if (!MasumiCose.verifySellerTermsSignature((String) extra.get("referenceKey"), (String) extra.get("referenceSignature"),
                termsDigest, (String) terms.get("sellerAddress"))) return Optional.of(ErrorCodes.MASUMI_AUTHORIZATION);

        // Call operator code only after authenticating the seller. Empty configuration grants no custom trust domain.
        Set<String> allowed = allowedScriptHashesByNetwork.getOrDefault(CardanoNetworks.normalize(requirements.network()), Set.of());
        boolean allowlisted = allowed.contains(ScriptAddress.scriptPaymentCredentialHex(requirements.payTo()));
        if (!allowed.isEmpty() && !allowlisted) return Optional.of(ErrorCodes.MASUMI_CONTRACT_MISMATCH);
        if (extra.containsKey("deployment") && !allowlisted) {
            try {
                if (deploymentValidator == null || !deploymentValidator.validate(new MasumiDeploymentValidator.Context(
                        requirements.network(), requirements.payTo(), deployment))) return Optional.of(ErrorCodes.MASUMI_DEPLOYMENT);
            } catch (RuntimeException e) { return Optional.of(ErrorCodes.MASUMI_DEPLOYMENT); }
        }
        String agent = terms.get("agentIdentifier") instanceof String a ? a : "";
        if (!agent.isEmpty()) {
            if (!agent.startsWith(REGISTRY_POLICY) || registryValidator == null || resource == null) {
                return Optional.of(ErrorCodes.MASUMI_AGENT_IDENTIFIER);
            }
            try {
                if (!registryValidator.validate(new MasumiRegistryValidator.Context(requirements,
                        Collections.unmodifiableMap(new LinkedHashMap<>(terms)), Collections.unmodifiableMap(new LinkedHashMap<>(resource)),
                        requirements.network()))) return Optional.of(ErrorCodes.MASUMI_AGENT_IDENTIFIER);
            } catch (RuntimeException e) { return Optional.of(ErrorCodes.MASUMI_AGENT_IDENTIFIER); }
        }
        var parts = MasumiIdentifier.decode((String) extra.get("blockchainIdentifier"));
        if (parts == null || !parts.sellerNonce().equals(terms.get("sellerNonce")) || !parts.agentIdentifier().equals(agent)
                || !parts.buyerNonce().equals(terms.get("buyerNonce")) || !parts.referenceSignature().equals(extra.get("referenceSignature"))
                || !parts.referenceKey().equals(extra.get("referenceKey")) || !parts.contractAddress().equals(requirements.payTo())) {
            return Optional.of(ErrorCodes.MASUMI_IDENTIFIER);
        }
        return Optional.empty();
    }

    private static boolean freshLockInvariantsViolated(MasumiDatumView view) {
        if (view.state() != MasumiDatum.STATE_FUNDS_LOCKED || !view.resultHash().isEmpty()
                || view.sellerCooldownTime().signum() != 0 || view.buyerCooldownTime().signum() != 0
                || view.referenceSignature().length() < 32) return true;
        for (var address : new MasumiAddressCredentials[] { view.buyer(), view.seller(), view.buyerReturnAddress(), view.sellerReturnAddress() }) {
            if (address != null && (address.payment().isScript() || address.stakeIsScript())) return true;
        }
        return !deadlineIntervals(view.payByTime(), view.submitResultTime(), view.unlockTime(), view.externalDisputeUnlockTime());
    }

    private static boolean deadlineIntervals(BigInteger payBy, BigInteger submit, BigInteger unlock, BigInteger dispute) {
        return payBy.add(MasumiConstants.MASUMI_MIN_PAY_TO_SUBMIT_MS).compareTo(submit) <= 0
                && submit.add(MasumiConstants.MASUMI_MIN_SUBMIT_TO_UNLOCK_MS).compareTo(unlock) <= 0
                && unlock.add(MasumiConstants.MASUMI_MIN_UNLOCK_TO_DISPUTE_MS).compareTo(dispute) <= 0;
    }

    private static boolean fieldsMismatch(Map<String, Object> extra, MasumiDatumView view) {
        var terms = object(extra, "terms");
        String agent = terms.get("agentIdentifier") instanceof String a ? a : "";
        return !MasumiDatum.sameCredentials(view.seller(), MasumiDatum.addressCredentials((String) terms.get("sellerAddress")))
                || !MasumiDatum.returnAddressMatches((String) terms.get("sellerReturnAddress"), view.sellerReturnAddress())
                || !extra.get("referenceKey").equals(view.referenceKey()) || !extra.get("referenceSignature").equals(view.referenceSignature())
                || !terms.get("sellerNonce").equals(view.sellerNonce()) || !terms.get("buyerNonce").equals(view.buyerNonce())
                || !agent.equals(view.agentIdentifier()) || !terms.get("inputHash").equals(view.inputHash())
                || !new BigInteger((String) terms.get("payByTime")).equals(view.payByTime())
                || !new BigInteger((String) terms.get("submitResultTime")).equals(view.submitResultTime())
                || !new BigInteger((String) terms.get("unlockTime")).equals(view.unlockTime())
                || !new BigInteger((String) terms.get("externalDisputeUnlockTime")).equals(view.externalDisputeUnlockTime());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> extra, String field) { return (Map<String, Object>) extra.get(field); }

    private static MasumiBlueprint.MasumiDeployment resolveDeployment(Map<String, Object> extra, String network) {
        if (extra.containsKey("deployment")) {
            var d = object(extra, "deployment");
            @SuppressWarnings("unchecked") List<String> keys = (List<String>) d.get("adminVkeys");
            return new MasumiBlueprint.MasumiDeployment(new BigInteger((String) d.get("requiredAdmins")),
                    List.copyOf(keys), new BigInteger((String) d.get("cooldownPeriod")));
        }
        return CardanoNetworks.PREVIEW.equals(CardanoNetworks.normalize(network)) ? null : MasumiBlueprint.DEFAULT_DEPLOYMENT;
    }

    private NetworkClock clockFor(String network) {
        NetworkClock clock = clocksByNetwork.get(CardanoNetworks.normalize(network));
        return clock != null ? clock : ShelleyNetworkClock.forNetwork(network, null);
    }
}
