package org.cardanofoundation.x402.facilitator.service.verification.method.masumi;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Closed-object validation for the Masumi {@code extra} block.
 *
 * <p>{@code extra}, {@code inputCommitment}, every commitment part, {@code terms},
 * {@code confirmationPolicy} and {@code deployment} are closed: an unknown field
 * is a rejection, not something to ignore. Ignoring unknown fields is how a
 * sender smuggles meaning past a verifier — the field looks honoured to whoever
 * wrote it and is invisible to whoever checks it.
 *
 * <p>The key sets also exclude every field projected into {@code signedTerms}
 * from the top level, so a {@code terms} block cannot restate (and thereby
 * contradict) the price, asset, network or escrow it is supposed to be bound to.
 *
 * <p>Mirrors {@code schema.ts} in the TypeScript reference implementation.
 */
final class MasumiSchema {

    private static final Set<String> EXTRA_KEYS = Set.of(
            "assetTransferMethod", "submissionPolicy", "confirmationPolicy", "areFeesSponsored",
            "inputCommitment", "terms", "referenceKey", "referenceSignature",
            "blockchainIdentifier", "deployment");

    private static final Set<String> COMMITMENT_KEYS = Set.of("version", "algorithm", "parts", "digest");

    private static final Set<String> PART_KEYS = Set.of(
            "name", "canonicalization", "mediaType", "content", "digest");

    private static final Set<String> TERMS_KEYS = Set.of(
            "version", "paymentType", "sellerAddress", "sellerReturnAddress", "sellerNonce",
            "buyerNonce", "agentIdentifier", "inputHash", "payByTime", "submitResultTime",
            "unlockTime", "externalDisputeUnlockTime", "settlementPolicy");

    private static final Set<String> DEPLOYMENT_KEYS = Set.of(
            "requiredAdmins", "adminVkeys", "cooldownPeriod");

    private static final Set<String> CONFIRMATION_POLICY_KEYS = Set.of("l1Confirmations");

    private MasumiSchema() {
    }

    /**
     * Validates the shape of a Masumi extra block.
     *
     * @param extra the extra block from the canonical requirements.
     * @return empty when the shape is valid, otherwise a description of the fault.
     */
    static Optional<String> validate(Map<String, Object> extra) {
        if (extra == null) return Optional.of("extra is absent");
        Optional<String> unknown = rejectUnknown(extra, EXTRA_KEYS, "extra");
        if (unknown.isPresent()) return unknown;

        if (!"masumi".equals(extra.get("assetTransferMethod"))) {
            return Optional.of("extra.assetTransferMethod must be masumi");
        }
        // Cardano never sponsors fees: the client balances the fee against its
        // own inputs, so a 402 claiming otherwise misdescribes who pays.
        Object sponsored = extra.get("areFeesSponsored");
        if (sponsored != null && !Boolean.FALSE.equals(sponsored) && !"false".equals(String.valueOf(sponsored))) {
            return Optional.of("extra.areFeesSponsored must be false");
        }
        Object submissionPolicy = extra.get("submissionPolicy");
        if (submissionPolicy != null
                && !List.of("server", "client", "either").contains(String.valueOf(submissionPolicy))) {
            return Optional.of("extra.submissionPolicy must be server, client or either");
        }
        if (extra.get("confirmationPolicy") instanceof Map<?, ?> policy) {
            Optional<String> bad = rejectUnknown(policy, CONFIRMATION_POLICY_KEYS, "extra.confirmationPolicy");
            if (bad.isPresent()) return bad;
            Object l1 = policy.get("l1Confirmations");
            if (!(l1 instanceof Number n) || n.longValue() < -1 || n.longValue() > 20) {
                return Optional.of("extra.confirmationPolicy.l1Confirmations must be -1..20");
            }
        }

        Object termsObj = extra.get("terms");
        if (!(termsObj instanceof Map<?, ?> terms)) return Optional.of("extra.terms is required");
        Optional<String> badTerms = rejectUnknown(terms, TERMS_KEYS, "extra.terms");
        if (badTerms.isPresent()) return badTerms;
        // Selects the contract generation; not advisory, so any other value is a
        // rejection rather than something to fall back from.
        if (!"Web3CardanoV2".equals(terms.get("paymentType"))) {
            return Optional.of("extra.terms.paymentType must be Web3CardanoV2");
        }
        Object settlementPolicy = terms.get("settlementPolicy");
        if (settlementPolicy != null
                && !List.of("auto", "l1", "hydra").contains(String.valueOf(settlementPolicy))) {
            return Optional.of("extra.terms.settlementPolicy must be auto, l1 or hydra");
        }

        if (extra.get("inputCommitment") instanceof Map<?, ?> commitment) {
            Optional<String> bad = rejectUnknown(commitment, COMMITMENT_KEYS, "extra.inputCommitment");
            if (bad.isPresent()) return bad;
            Object parts = commitment.get("parts");
            if (!(parts instanceof List<?> list) || list.isEmpty()) {
                return Optional.of("extra.inputCommitment.parts must be a non-empty array");
            }
            for (Object partObj : list) {
                if (!(partObj instanceof Map<?, ?> part)) {
                    return Optional.of("extra.inputCommitment.parts entries must be objects");
                }
                Optional<String> badPart = rejectUnknown(part, PART_KEYS, "extra.inputCommitment.parts[]");
                if (badPart.isPresent()) return badPart;
                Object canonicalization = part.get("canonicalization");
                if (!List.of("jcs", "raw").contains(String.valueOf(canonicalization))) {
                    return Optional.of("commitment part canonicalization must be jcs or raw");
                }
            }
        }

        if (extra.get("deployment") instanceof Map<?, ?> deployment) {
            Optional<String> bad = rejectUnknown(deployment, DEPLOYMENT_KEYS, "extra.deployment");
            if (bad.isPresent()) return bad;
            if (!(deployment.get("adminVkeys") instanceof List<?> keys) || keys.isEmpty()) {
                return Optional.of("extra.deployment.adminVkeys must be a non-empty array");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> rejectUnknown(Map<?, ?> object, Set<String> allowed, String path) {
        for (Object key : object.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                return Optional.of(path + " carries an unknown field: " + key);
            }
        }
        return Optional.empty();
    }
}
