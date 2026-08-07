package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.cardanofoundation.x402.facilitator.chain.NetworkClock;
import org.cardanofoundation.x402.facilitator.chain.ShelleyNetworkClock;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;
import org.cardanofoundation.x402.facilitator.service.verification.method.ExtraValues;
import org.cardanofoundation.x402.facilitator.service.verification.method.TransferMethodVerifier;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiDatum.MasumiAddressCredentials;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiDatum.MasumiDatumView;
import org.cardanofoundation.x402.facilitator.service.verification.method.script.ScriptAddress;

import lombok.RequiredArgsConstructor;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code masumi} assetTransferMethod verifier (rules M1-M9): confirms a
 * payment locks funds into the Masumi {@code vested_pay} escrow with a
 * well-formed {@code FundsLocked} datum matching the requirements. Only the
 * on-chain lock is checked (x402's scope); the post-lock lifecycle is out of
 * scope.
 *
 * <p>M1 also validates {@code payTo}'s script credential against a configured
 * per-network allowlist, and the M8 deadline honors a per-network slot-config
 * override.
 */
@RequiredArgsConstructor
public class MasumiTransferVerifier implements TransferMethodVerifier {

    /** network id -> configured clock (applies any per-network slot-config override); may be empty. */
    private final Map<String, NetworkClock> clocksByNetwork;
    /** network id -> allowed escrow script-credential hashes (lowercase); empty for a network = no
     *  allowlist enforcement (self-consistency only). */
    private final Map<String, Set<String>> allowedScriptHashesByNetwork;

    /** Default: no allowlist enforced, built-in slot configs. */
    public MasumiTransferVerifier() {
        this(Map.of(), Map.of());
    }

    @Override
    public boolean supports(String method) {
        return "masumi".equals(method);
    }

    @Override
    public Optional<String> check(Map<String, Object> extra, PaymentRequirements requirements,
                                  DecodedTransaction tx, String payer, BigInteger coinsPerUtxoByte) {
        // 1. payTo MUST equal the escrow address this verifier DERIVES by applying
        //    the deployment parameters to the canonical vested_pay validator. A
        //    server-declared `contractAddress` is only a claim: trusting it would
        //    let a resource server aim the lock at any address it liked, and the
        //    buyer's funds would settle outside the contract meant to govern their
        //    release. When declared, it must agree with the derivation.
        // The spec's Masumi extra nests the seller-signed material under `terms`
        // and the request commitment under `inputCommitment`. Verify both before
        // anything structural: a datum that matches a set of terms nobody signed
        // is worthless, and the older flat shape carries no proof of consent.
        Optional<String> authorization = verifyAuthorization(extra, requirements);
        if (authorization.isPresent()) return authorization;
        extra = normalizeExtra(extra);

        MasumiBlueprint.MasumiDeployment deployment = resolveDeployment(extra, requirements.network());
        if (deployment == null) {
            // Preview has no canonical deployment, so an explicit one is required.
            return Optional.of(ErrorCodes.MASUMI_CONTRACT_MISMATCH);
        }
        String derivedEscrow;
        try {
            derivedEscrow = MasumiBlueprint.escrowAddress(requirements.network(), deployment);
        } catch (RuntimeException e) {
            return Optional.of(ErrorCodes.MASUMI_CONTRACT_MISMATCH);
        }
        if (!derivedEscrow.equals(requirements.payTo())) {
            return Optional.of(ErrorCodes.MASUMI_CONTRACT_MISMATCH);
        }
        String contractAddress = str(extra, "contractAddress");
        if (contractAddress != null && !contractAddress.equals(derivedEscrow)) {
            return Optional.of(ErrorCodes.MASUMI_CONTRACT_MISMATCH);
        }
        contractAddress = derivedEscrow;
        // 1b. When an allowlist is configured for this network, payTo MUST be a
        //     script address whose credential is allowed — otherwise a server
        //     could declare an arbitrary escrow and strand funds.
        Set<String> allowed = allowedScriptHashesByNetwork.get(CardanoNetworks.normalize(requirements.network()));
        if (allowed != null && !allowed.isEmpty()) {
            String credential = ScriptAddress.scriptPaymentCredentialHex(requirements.payTo());
            if (credential == null || !allowed.contains(credential)) {
                return Optional.of(ErrorCodes.MASUMI_CONTRACT_MISMATCH);
            }
        }

        // 2. Locate the escrow output paying payTo and carrying an inline datum.
        DecodedTransaction.Output output = null;
        for (DecodedTransaction.Output o : tx.outputs()) {
            if (o.address().equals(requirements.payTo()) && o.raw().getInlineDatum() != null) {
                output = o;
                break;
            }
        }
        if (output == null) return Optional.of(ErrorCodes.MASUMI_DATUM_MISSING);
        // The escrow output MUST NOT carry a reference script (Masumi treats a set
        // reference_script_hash as spoofing / FundsOrDatumInvalid).
        if (output.raw().getScriptRef() != null) return Optional.of(ErrorCodes.MASUMI_REFERENCE_SCRIPT);

        PlutusData datum = output.raw().getInlineDatum();
        MasumiDatumView view = MasumiDatum.parse(datum);
        if (view == null) return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);

        // 3. Structural invariants of a fresh lock.
        if (freshLockInvariantsViolated(view)) return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);

        // 3b. No participant or return address may BE the escrow. vested_pay
        //     re-parses every output at the script address as a continuation datum
        //     (`expect new_datum: Datum`), so a payout aimed back at the escrow
        //     aborts every spend path; Masumi's own decodeV2ContractDatum rejects
        //     such datums the same way. Anyone can lock this datum directly
        //     on-chain, so reject before a seller treats it as paid and works.
        MasumiAddressCredentials escrow = MasumiDatum.addressCredentials(contractAddress);
        if (MasumiDatum.sameCredentials(view.buyer(), escrow)
                || MasumiDatum.sameCredentials(view.seller(), escrow)
                || (view.buyerReturnAddress() != null && MasumiDatum.sameCredentials(view.buyerReturnAddress(), escrow))
                || (view.sellerReturnAddress() != null && MasumiDatum.sameCredentials(view.sellerReturnAddress(), escrow))) {
            return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);
        }
        // This scheme does not allow aggregated payouts: the effective buyer
        // payout target (buyer_return_address, else buyer) MUST differ from the
        // effective seller target. Equal targets make refund and release pay the
        // same party.
        MasumiAddressCredentials buyerTarget =
                view.buyerReturnAddress() != null ? view.buyerReturnAddress() : view.buyer();
        MasumiAddressCredentials sellerTarget =
                view.sellerReturnAddress() != null ? view.sellerReturnAddress() : view.seller();
        if (MasumiDatum.sameCredentials(buyerTarget, sellerTarget)) {
            return Optional.of(ErrorCodes.MASUMI_DATUM_INVALID);
        }

        // 4. Deadline: the tx MUST carry a validity upper bound (TTL) on/before
        //    pay_by_time. Uses the per-network configured clock (slot-config override).
        if (tx.ttlSlot() == null) return Optional.of(ErrorCodes.MASUMI_DEADLINE);
        long ttlPosixMs = clockFor(requirements.network()).slotToTime(tx.ttlSlot()).toEpochMilli();
        if (BigInteger.valueOf(ttlPosixMs).compareTo(view.payByTime()) > 0) {
            return Optional.of(ErrorCodes.MASUMI_DEADLINE);
        }

        // 5. Value: collateral bounds + asset/amount.
        BigInteger collateral = view.collateralReturnLovelace();
        if (collateral.signum() < 0
                || (collateral.signum() > 0 && collateral.compareTo(MasumiConstants.MASUMI_MIN_COLLATERAL_LOVELACE) < 0)
                || collateral.compareTo(output.coin()) > 0) {
            return Optional.of(ErrorCodes.MASUMI_COLLATERAL);
        }
        BigInteger amount = new BigInteger(requirements.amount());
        String assetKey = TransferMethodVerifier.assetKey(requirements);
        boolean isLovelace = TransferMethodVerifier.isLovelace(assetKey);
        if (isLovelace) {
            if (output.coin().compareTo(amount.add(collateral)) < 0) return Optional.of(ErrorCodes.MASUMI_ASSET);
        } else {
            BigInteger held = output.assets().get(assetKey);
            if (held == null || held.compareTo(amount) != 0) return Optional.of(ErrorCodes.MASUMI_ASSET);
        }
        if (output.assets().size() != (isLovelace ? 0 : 1)) return Optional.of(ErrorCodes.MASUMI_ASSET);

        // 6. min-UTXO with post-result headroom, computed on the ON-CHAIN datum byte
        //    length, not cardano-client-lib's re-serialization.
        if (coinsPerUtxoByte != null) {
            int nativeTokenCount = output.assets().size();
            BigInteger requiredMinUtxo = MasumiConstants.masumiMinUtxoLovelace(
                    output.inlineDatumRawLen(), nativeTokenCount, coinsPerUtxoByte);
            if (output.coin().compareTo(requiredMinUtxo) < 0) return Optional.of(ErrorCodes.MASUMI_MIN_UTXO);
        }

        // 7. Field matching against the canonical requirements' extra.
        if (fieldsMismatch(extra, view, payer)) {
            return Optional.of(ErrorCodes.MASUMI_DATUM_MISMATCH);
        }
        return Optional.empty();
    }

    /**
     * M3 structural invariants of a fresh {@code FundsLocked} lock: correct state, empty
     * result hash, zero cooldowns, key-credential (not script) parties, a reference
     * signature of at least 16 bytes, and monotonic deadlines
     * ({@code payBy <= submitResult <= unlock <= externalDisputeUnlock}).
     */
    private static boolean freshLockInvariantsViolated(MasumiDatumView view) {
        if (view.state() != MasumiDatum.STATE_FUNDS_LOCKED) return true;
        if (!view.resultHash().isEmpty()) return true;
        // Fresh lock: both cooldown timers MUST be 0 (a non-zero value is spoofing).
        if (view.sellerCooldownTime().signum() != 0 || view.buyerCooldownTime().signum() != 0) return true;
        if (view.buyer().payment().isScript() || view.seller().payment().isScript()) return true;
        // reference_signature: >= 16 bytes (32 hex chars).
        if (view.referenceSignature().length() < 32) return true;
        // Deadlines: ordered AND clearing the spec's minimum intervals. Ordering
        // alone admits a lock whose windows collapse to zero, leaving no room to
        // submit a result or contest one.
        return view.payByTime().add(MasumiConstants.MASUMI_MIN_PAY_TO_SUBMIT_MS)
                        .compareTo(view.submitResultTime()) > 0
                || view.submitResultTime().add(MasumiConstants.MASUMI_MIN_SUBMIT_TO_UNLOCK_MS)
                        .compareTo(view.unlockTime()) > 0
                || view.unlockTime().add(MasumiConstants.MASUMI_MIN_UNLOCK_TO_DISPUTE_MS)
                        .compareTo(view.externalDisputeUnlockTime()) > 0;
    }

    /**
     * M7 field matching against the canonical requirements' extra. The buyer/seller
     * credentials and {@code seller_return_address} are always asserted; the remaining
     * hex/int fields are only checked when declared (see the class javadoc for the
     * semantics).
     *
     * <p>{@code buyer_return_address} is deliberately NOT matched. It is buyer-supplied:
     * the 402 answers an unauthenticated request, so the resource server does not know
     * the payer and cannot declare its refund address. The buyer stays pinned by the
     * {@code buyer == payer} assertion above.
     */
    private static boolean fieldsMismatch(Map<String, Object> extra, MasumiDatumView view, String payer) {
        if (!MasumiDatum.sameCredentials(view.buyer(), MasumiDatum.addressCredentials(payer))) return true;
        String sellerAddress = str(extra, "sellerAddress");
        if (sellerAddress == null
                || !MasumiDatum.sameCredentials(view.seller(), MasumiDatum.addressCredentials(sellerAddress))) {
            return true;
        }
        if (!MasumiDatum.returnAddressMatches(str(extra, "sellerReturnAddress"), view.sellerReturnAddress())) {
            return true;
        }
        if (hexMismatch(extra, "referenceKey", view.referenceKey())
                || hexMismatch(extra, "referenceSignature", view.referenceSignature())
                || hexMismatch(extra, "sellerNonce", view.sellerNonce())
                || hexMismatch(extra, "identifierFromPurchaser", view.buyerNonce())
                || hexMismatch(extra, "agentIdentifier", view.agentIdentifier())
                || hexMismatch(extra, "inputHash", view.inputHash())) {
            return true;
        }
        return intMismatch(extra, "payByTime", view.payByTime())
                || intMismatch(extra, "submitResultTime", view.submitResultTime())
                || intMismatch(extra, "unlockTime", view.unlockTime())
                || intMismatch(extra, "externalDisputeUnlockTime", view.externalDisputeUnlockTime())
                || intMismatch(extra, "collateralReturnLovelace", view.collateralReturnLovelace());
    }

    private NetworkClock clockFor(String network) {
        NetworkClock clock = clocksByNetwork.get(CardanoNetworks.normalize(network));
        return clock != null ? clock : ShelleyNetworkClock.forNetwork(network, null);
    }

    private static String str(Map<String, Object> extra, String key) {
        Object v = extra == null ? null : extra.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** True when a declared hex field is present and does NOT match the datum value. */
    private static boolean hexMismatch(Map<String, Object> extra, String key, String actualLowerHex) {
        String declared = str(extra, key);
        return declared != null && !declared.toLowerCase().equals(actualLowerHex);
    }

    /** True when a declared integer field is present and does NOT match the datum value. */
    private static boolean intMismatch(Map<String, Object> extra, String key, BigInteger actual) {
        Object declared = extra == null ? null : extra.get(key);
        return declared != null && !ExtraValues.toBigInteger(declared).equals(actual);
    }

    /**
     * Resolves the deployment parameters to apply: the explicitly declared
     * {@code extra.deployment} when present, otherwise the canonical default.
     * Preview has no canonical deployment, so an absent one there yields null.
     *
     * @param extra the requirements' extra block.
     * @param network the x402 Cardano network identifier.
     * @return the deployment to apply, or null when none can be resolved.
     */
    private static MasumiBlueprint.MasumiDeployment resolveDeployment(Map<String, Object> extra,
                                                                      String network) {
        Object declared = extra == null ? null : extra.get("deployment");
        if (declared instanceof Map<?, ?> d) {
            try {
                Object admins = d.get("adminVkeys");
                if (!(admins instanceof List<?> list) || list.isEmpty()) return null;
                List<String> vkeys = list.stream().map(String::valueOf).toList();
                return new MasumiBlueprint.MasumiDeployment(
                        new BigInteger(String.valueOf(d.get("requiredAdmins"))),
                        vkeys,
                        new BigInteger(String.valueOf(d.get("cooldownPeriod"))));
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (CardanoNetworks.PREVIEW.equals(CardanoNetworks.normalize(network))) return null;
        return MasumiBlueprint.DEFAULT_DEPLOYMENT;
    }

    /**
     * Verifies the request commitment and the seller's authorization over
     * {@code termsDigest}.
     *
     * <p>This is what makes the datum meaningful. Comparing declared bytes
     * against datum bytes only proves the requirements repeat themselves — the
     * resource server writes both. Recomputing the commitment proves the escrow
     * is bound to the job that was actually requested, and verifying the COSE
     * signature proves the seller agreed to these exact terms at this exact
     * price, asset, network and escrow.
     *
     * @param extra the Masumi extra block.
     * @param requirements the canonical requirements.
     * @return empty when authorized, otherwise the rejection code.
     */
    private static Optional<String> verifyAuthorization(Map<String, Object> extra,
                                                        PaymentRequirements requirements) {
        // Shape first: a closed-object check means an unknown field is a
        // rejection rather than something silently ignored.
        if (MasumiSchema.validate(extra).isPresent()) {
            return Optional.of(ErrorCodes.MASUMI_SCHEMA);
        }
        Object termsObj = extra.get("terms");
        if (!(termsObj instanceof Map<?, ?> rawTerms)) {
            // No signed terms: nothing binds the seller to this payment.
            return Optional.of(ErrorCodes.MASUMI_AUTHORIZATION);
        }
        Map<String, Object> terms = new LinkedHashMap<>();
        rawTerms.forEach((k, v) -> terms.put(String.valueOf(k), v));

        // Commitment: every part digest recomputes, the manifest digest
        // recomputes, and terms.inputHash equals it.
        Object commitmentObj = extra.get("inputCommitment");
        if (commitmentObj instanceof Map<?, ?> rawCommitment) {
            Map<String, Object> commitment = new LinkedHashMap<>();
            rawCommitment.forEach((k, v) -> commitment.put(String.valueOf(k), v));
            Object parts = commitment.get("parts");
            if (parts instanceof List<?> list) {
                for (Object partObj : list) {
                    if (!(partObj instanceof Map<?, ?> rawPart)) {
                        return Optional.of(ErrorCodes.MASUMI_COMMITMENT);
                    }
                    Map<String, Object> part = new LinkedHashMap<>();
                    rawPart.forEach((k, v) -> part.put(String.valueOf(k), v));
                    // A part whose content the issuer withheld cannot be checked
                    // here; the facilitator never saw the buyer's request.
                    if (part.get("content") == null) continue;
                    String declared = String.valueOf(part.get("digest"));
                    try {
                        if (!declared.equalsIgnoreCase(MasumiDigests.commitmentPartDigest(part))) {
                            return Optional.of(ErrorCodes.MASUMI_COMMITMENT);
                        }
                    } catch (RuntimeException e) {
                        return Optional.of(ErrorCodes.MASUMI_COMMITMENT);
                    }
                }
            }
            try {
                String recomputed = MasumiDigests.computeInputHash(commitment);
                if (!recomputed.equalsIgnoreCase(String.valueOf(commitment.get("digest")))
                        || !recomputed.equalsIgnoreCase(String.valueOf(terms.get("inputHash")))) {
                    return Optional.of(ErrorCodes.MASUMI_COMMITMENT);
                }
            } catch (RuntimeException e) {
                return Optional.of(ErrorCodes.MASUMI_COMMITMENT);
            }
        }

        // Seller authorization over the reconstructed terms digest.
        String sellerAddress = String.valueOf(terms.get("sellerAddress"));
        String referenceKey = str(extra, "referenceKey");
        String referenceSignature = str(extra, "referenceSignature");
        if (referenceKey == null || referenceSignature == null || sellerAddress == null) {
            return Optional.of(ErrorCodes.MASUMI_AUTHORIZATION);
        }
        String termsDigest;
        try {
            termsDigest = MasumiDigests.computeTermsDigest(
                    MasumiDigests.buildSignedTerms(extra, requirements));
        } catch (RuntimeException e) {
            return Optional.of(ErrorCodes.MASUMI_AUTHORIZATION);
        }
        if (!MasumiCose.verifySellerTermsSignature(
                referenceKey, referenceSignature, termsDigest, sellerAddress)) {
            return Optional.of(ErrorCodes.MASUMI_AUTHORIZATION);
        }

        // The compatibility identifier is a third, independent statement of where
        // the funds go. It must name the same escrow as payTo, or the three
        // sources disagree and one of them is lying.
        String identifier = str(extra, "blockchainIdentifier");
        if (identifier != null && !identifier.isEmpty()) {
            MasumiIdentifier.IdentifierParts parts = MasumiIdentifier.decode(identifier);
            if (parts == null || !requirements.payTo().equals(parts.contractAddress())) {
                return Optional.of(ErrorCodes.MASUMI_IDENTIFIER);
            }
        }
        return Optional.empty();
    }

    /**
     * Flattens the spec's nested extra into the field names the structural datum
     * matching below uses, so one comparison path serves both shapes.
     *
     * @param extra the Masumi extra block.
     * @return a flat view carrying the signed terms plus the top-level references.
     */
    private static Map<String, Object> normalizeExtra(Map<String, Object> extra) {
        Map<String, Object> flat = new LinkedHashMap<>(extra);
        if (extra.get("terms") instanceof Map<?, ?> terms) {
            terms.forEach((k, v) -> flat.put(String.valueOf(k), v));
            // The datum field is `buyer_nonce`; MIP-004 called it
            // identifierFromPurchaser and the old flat schema kept that name.
            if (terms.get("buyerNonce") != null) {
                flat.put("identifierFromPurchaser", terms.get("buyerNonce"));
            }
        }
        return flat;
    }
}
