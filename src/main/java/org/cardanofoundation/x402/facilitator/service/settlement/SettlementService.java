package org.cardanofoundation.x402.facilitator.service.settlement;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord.Status;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord.SubmissionProvenance;
import org.cardanofoundation.x402.facilitator.model.protocol.*;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;
import org.cardanofoundation.x402.facilitator.repository.SettlementRepository;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;
import org.cardanofoundation.x402.facilitator.service.verification.CardanoPolicies;
import org.cardanofoundation.x402.facilitator.service.verification.ExactCardanoScheme;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import org.cardanofoundation.x402.facilitator.service.verification.method.masumi.MasumiDigests;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** Durable, fenced single-broadcast settlement with freshly checked evidence on every retry. */
@Log4j2
@RequiredArgsConstructor
public class SettlementService {
    /** confirmationDepth/idempotentReplay are retained for configuration migration only. */
    public record Config(Duration confirmationTimeout, int confirmationDepth, boolean acceptMempool,
                         boolean idempotentReplay, Duration stabilityWindow, Duration claimTtl) { }

    private final SettlementRepository repo;
    private final ExactCardanoScheme scheme;
    private final FacilitatorChainService chain;
    private final CardanoTransactionDecoder decoder;
    private final Config config;
    private final Clock clock;

    public SettleResponse settle(PaymentPayload payload, PaymentRequirements requirements) {
        String network = CardanoNetworks.normalize(requirements.network());
        Integer depth = CardanoPolicies.l1Confirmations(requirements.extra());
        if (depth == null) return SettleResponse.fail(ErrorCodes.REQUIREMENTS_POLICY, null, network);
        String txB64 = str(payload.payload(), "transaction");
        if (txB64 == null || txB64.isEmpty()) return SettleResponse.fail(ErrorCodes.INVALID_PAYLOAD, null, network);
        DecodedTransaction tx;
        try {
            tx = decoder.decode(txB64);
        } catch (CardanoTransactionDecoder.TransactionDecodeException e) {
            return SettleResponse.fail(ErrorCodes.DECODE_FAILED, e.getMessage(), network);
        }
        String hash = tx.txHashHex().toLowerCase();
        String digest = SettlementDigest.compute(requirements, payload.resource());
        SettlementRecord existing = repo.find(hash).orElse(null);
        if (existing != null && !reclaimable(existing)) {
            return resume(existing, payload, requirements, digest, depth, network);
        }

        VerifyResponse verified = scheme.verify(payload, requirements);
        if (!verified.isValid()) return invalid(verified, network);
        String terms = termsDigest(requirements);
        UUID attempt = UUID.randomUUID();
        SettlementRecord claim = new SettlementRecord(hash, attempt, digest, network, Status.CLAIMED,
                verified.payer(), requirements.payTo(), requirements.asset(), new BigDecimal(requirements.amount()),
                method(requirements), normalizedNonce(str(payload.payload(), "nonce")), tx.ttlSlot(), clock.instant(),
                null, null, null, null, null, null, depth, SubmissionProvenance.LOCAL, false, terms);
        if (!repo.insertClaim(claim) && !repo.reclaim(claim, config.claimTtl())) {
            SettlementRecord winner = repo.find(hash).orElse(null);
            return winner == null ? SettleResponse.fail(ErrorCodes.DUPLICATE_SETTLEMENT,
                    "Masumi terms already claimed by another transaction", network)
                    : resume(winner, payload, requirements, digest, depth, network);
        }
        // The durable fence precedes all wire I/O. A paused former owner cannot enter this path.
        if (!repo.casTransition(hash, attempt, Status.CLAIMED, Status.SUBMITTING, Map.of())) {
            return SettleResponse.pending(hash, network, verified.payer());
        }
        SubmissionResult submission;
        try {
            submission = chain.submitTransaction(Base64.getDecoder().decode(txB64));
        } catch (RuntimeException e) {
            // Unclassified provider exceptions cannot prove that nothing was sent.
            log.warn("unclassified submission outcome for {}", hash);
            return SettleResponse.pending(hash, network, verified.payer());
        }
        switch (submission) {
            case SubmissionResult.NotSubmitted noSend -> {
                repo.releaseUnsubmitted(hash, attempt);
                return SettleResponse.fail(ErrorCodes.SETTLEMENT_FAILED, noSend.cause(), network);
            }
            case SubmissionResult.Rejected rejected -> {
                repo.casTransition(hash, attempt, Status.SUBMITTING, Status.FAILED,
                        Map.of("error_reason", ErrorCodes.SETTLEMENT_DEFINITIVELY_REJECTED));
                return SettleResponse.failWithTx(ErrorCodes.SETTLEMENT_DEFINITIVELY_REJECTED,
                        hash, network, verified.payer(), null);
            }
            case SubmissionResult.Unknown ignored -> {
                return SettleResponse.pending(hash, network, verified.payer());
            }
            case SubmissionResult.Accepted accepted -> {
                if (!hash.equalsIgnoreCase(accepted.txHash())) {
                    // An unrelated provider hash proves no acceptance of these bytes.
                    return SettleResponse.pending(hash, network, verified.payer());
                }
                repo.casTransition(hash, attempt, Status.SUBMITTING, Status.SUBMITTED,
                        Map.of("submitted_at", clock.instant(), "submission_accepted", true));
            }
        }
        SettlementRecord submitted = repo.find(hash).orElseThrow();
        if (Integer.valueOf(-1).equals(submitted.selectedConfirmations()) && config.acceptMempool()
                && submitted.submissionAccepted()) {
            return repo.recordObservation(submitted, submitted.status(), Map.of())
                    ? SettleResponse.ok(hash, network, verified.payer(), "mempool")
                    : SettleResponse.pending(hash, network, verified.payer(), -1);
        }
        try {
            InclusionResult evidence = chain.awaitInclusion(hash, depth, config.confirmationTimeout());
            if (evidence instanceof InclusionResult.NotSeen) evidence = chain.checkInclusion(hash);
            return observe(repo.find(hash).orElse(submitted), evidence, depth, network, verified.payer(), false);
        } catch (RuntimeException e) {
            return SettleResponse.pending(hash, network, verified.payer());
        }
    }

    private SettleResponse resume(SettlementRecord rec, PaymentPayload payload, PaymentRequirements requirements,
                                  String digest, int depth, String network) {
        boolean sameBinding = digest.equals(rec.requirementsDigest()) || (rec.isLegacy()
                && SettlementDigest.computeLegacy(requirements, payload.resource()).equals(rec.requirementsDigest()));
        if (!sameBinding) return SettleResponse.fail(ErrorCodes.DUPLICATE_SETTLEMENT,
                "transaction is bound to different payment requirements or resource", network);
        if (!rec.isLegacy() && rec.status() == Status.FAILED) {
            return SettleResponse.failWithTx(ErrorCodes.SETTLEMENT_DEFINITIVELY_REJECTED,
                    rec.txHash(), network, rec.payer(), null);
        }
        InclusionResult evidence;
        try {
            evidence = chain.checkInclusion(rec.txHash());
        } catch (RuntimeException e) {
            evidence = null; // Unknown is never absence, and never a reason to broadcast again.
        }
        boolean canonical = evidence instanceof InclusionResult.Included;
        boolean locallyVerifiedBroadcast = !rec.isLegacy() && rec.status() != Status.CLAIMED;
        VerifyResponse verified = locallyVerifiedBroadcast || canonical
                ? scheme.verifyBroadcast(payload, requirements, rec.isLegacy() && rec.selectedConfirmations() == null
                        ? null : rec.payer())
                : scheme.verify(payload, requirements);
        if (!verified.isValid()) {
            if (ErrorCodes.CHAIN_LOOKUP_FAILED.equals(verified.invalidReason())
                    || (rec.isLegacy() && !canonical && preBroadcastFailure(verified.invalidReason()))) {
                SettleResponse expired = expireIfProven(rec, evidence, network, rec.payer());
                return expired != null ? expired : SettleResponse.pending(rec.txHash(), network, rec.payer());
            }
            return invalid(verified, network);
        }
        if (!repo.bindVerifiedRetry(rec, depth, termsDigest(requirements), verified.payer())) {
            return SettleResponse.fail(ErrorCodes.DUPLICATE_SETTLEMENT, "payment terms claim conflict", network);
        }
        SettlementRecord current = repo.find(rec.txHash()).orElse(rec);
        if (evidence == null || (!current.isLegacy() && current.status() == Status.CLAIMED)) {
            return SettleResponse.pending(rec.txHash(), network, verified.payer());
        }
        return observe(current, evidence, depth, network, verified.payer(), true);
    }

    private SettleResponse observe(SettlementRecord rec, InclusionResult evidence, int requestedDepth,
                                   String network, String payer, boolean resumed) {
        if (resumed) {
            SettleResponse expired = expireIfProven(rec, evidence, network, payer);
            if (expired != null) return expired;
        }
        int depth = Math.max(requestedDepth, rec.selectedConfirmations() == null
                ? requestedDepth : rec.selectedConfirmations());
        if (evidence instanceof InclusionResult.Included included && included.depth() >= Math.max(0, depth)) {
            SettleResponse ok = SettleResponse.confirmed(rec.txHash(), network, payer, included.depth());
            boolean recorded = repo.recordObservation(rec, Status.CONFIRMED, Map.of(
                    "confirmed_at", clock.instant(), "confirmed_slot", included.slot(),
                    "confirmed_block", included.blockHash(), "response_json", json(ok), "payer", payer));
            return recorded ? ok : SettleResponse.pending(rec.txHash(), network, payer);
        }
        // Legacy V1 states never manufacture authenticated mempool acceptance.
        if (!rec.isLegacy() && depth == -1 && config.acceptMempool()
                && (evidence instanceof InclusionResult.Mempool
                    || (rec.submissionAccepted() && rec.confirmedAt() == null))) {
            return repo.recordObservation(rec, rec.status(), Map.of())
                    ? SettleResponse.ok(rec.txHash(), network, payer, "mempool")
                    : SettleResponse.pending(rec.txHash(), network, payer, -1);
        }
        if (rec.status() == Status.CONFIRMED) {
            repo.recordObservation(rec, Status.SUBMITTED, Map.of());
        } else if (rec.status() == Status.SUBMITTED) {
            repo.recordObservation(rec, Status.NOT_CONFIRMED, Map.of());
        }
        Integer observed = evidence instanceof InclusionResult.Included included ? included.depth()
                : evidence instanceof InclusionResult.Mempool ? -1 : null;
        if (observed == null && rec.submissionAccepted() && rec.confirmedAt() == null) observed = -1;
        return SettleResponse.pending(rec.txHash(), network, payer, observed);
    }

    /** A historical EXPIRED status alone is insufficient, especially for V1 horizon-expired rows. */
    private SettleResponse expireIfProven(SettlementRecord rec, InclusionResult evidence,
                                         String network, String payer) {
        if (!(evidence instanceof InclusionResult.NotSeen) || rec.txTtlSlot() == null) return null;
        long currentSlot;
        try {
            currentSlot = chain.getCurrentSlot();
        } catch (RuntimeException e) {
            return null;
        }
        if (currentSlot <= rec.txTtlSlot() || currentSlot - rec.txTtlSlot() <= 120) return null;
        if (!repo.recordObservation(rec, Status.EXPIRED, Map.of())) {
            return SettleResponse.pending(rec.txHash(), network, payer);
        }
        return SettleResponse.failWithTx(ErrorCodes.SETTLEMENT_FAILED, rec.txHash(), network, payer, "expired");
    }

    private boolean reclaimable(SettlementRecord rec) {
        return !rec.isLegacy() && rec.status() == Status.CLAIMED && !rec.submissionAccepted()
                && rec.submittedAt() == null && rec.claimedAt().isBefore(clock.instant().minus(config.claimTtl()));
    }

    private static boolean preBroadcastFailure(String error) {
        return ErrorCodes.NONCE_NOT_ON_CHAIN.equals(error) || ErrorCodes.INPUT_NOT_AVAILABLE.equals(error)
                || ErrorCodes.TTL_EXPIRED.equals(error) || ErrorCodes.NOT_YET_VALID.equals(error);
    }

    private static SettleResponse invalid(VerifyResponse verified, String network) {
        return SettleResponse.fail(verified.invalidReason(), verified.invalidMessage(), network);
    }

    private static String termsDigest(PaymentRequirements requirements) {
        return "masumi".equals(method(requirements)) ? MasumiDigests.computeTermsDigest(
                MasumiDigests.buildSignedTerms(requirements.extra(), requirements)) : null;
    }

    private static String method(PaymentRequirements requirements) {
        return requirements.extra() == null ? "default"
                : String.valueOf(requirements.extra().getOrDefault("assetTransferMethod", "default"));
    }

    private static String normalizedNonce(String nonce) {
        int sep = nonce.indexOf('#');
        return nonce.substring(0, sep).toLowerCase() + "#" + Integer.parseInt(nonce.substring(sep + 1));
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof String s ? s : null;
    }

    static String json(SettleResponse response) {
        try {
            return ProtocolJson.mapper().writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize settlement evidence", e);
        }
    }
}
