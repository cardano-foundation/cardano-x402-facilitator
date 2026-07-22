package org.cardanofoundation.x402.facilitator.service.settlement;

import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.model.ErrorCodes;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord.Status;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentPayload;
import org.cardanofoundation.x402.facilitator.model.protocol.PaymentRequirements;
import org.cardanofoundation.x402.facilitator.model.protocol.ProtocolJson;
import org.cardanofoundation.x402.facilitator.model.protocol.SettleResponse;
import org.cardanofoundation.x402.facilitator.model.protocol.VerifyResponse;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
import org.cardanofoundation.x402.facilitator.model.verification.DecodedTransaction;
import org.cardanofoundation.x402.facilitator.repository.SettlementRepository;
import org.cardanofoundation.x402.facilitator.service.verification.ExactCardanoScheme;
import org.cardanofoundation.x402.facilitator.service.verification.decoder.CardanoTransactionDecoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The settlement pipeline. Owns journal, claim, submission and inclusion;
 * depends one-way on the scheme for re-verification.
 */
@Log4j2
@RequiredArgsConstructor
public class SettlementService {

    public record Config(Duration confirmationTimeout, int confirmationDepth, boolean acceptMempool,
                         boolean idempotentReplay, Duration stabilityWindow, Duration claimTtl) {
    }

    private final SettlementRepository repo;
    private final ExactCardanoScheme scheme;
    private final FacilitatorChainService chain;
    private final CardanoTransactionDecoder decoder;
    private final Config config;
    private final Clock clock;

    public SettleResponse settle(PaymentPayload payload, PaymentRequirements requirements) {
        String network = payload.accepted() != null ? payload.accepted().network() : requirements.network();
        String txB64 = str(payload.payload(), "transaction");
        if (txB64 == null || txB64.isEmpty()) {
            return SettleResponse.fail(ErrorCodes.INVALID_PAYLOAD, null, network);
        }
        DecodedTransaction tx;
        try {
            tx = decoder.decode(txB64);
        } catch (CardanoTransactionDecoder.TransactionDecodeException e) {
            return SettleResponse.fail(ErrorCodes.DECODE_FAILED, e.getMessage(), network);
        }
        String txHash = tx.txHashHex().toLowerCase();
        String digest = SettlementDigest.compute(requirements, payload.resource());

        // Step 1 — journal lookup (idempotency/duplicate, digest-bound)
        Optional<SettlementRecord> existing = repo.find(txHash);
        if (existing.isPresent()) {
            SettleResponse short_ = handleExisting(existing.get(), payload, requirements, digest, network);
            if (short_ != null) return short_;
        }

        // Step 2 — full re-verification
        VerifyResponse verify = scheme.verify(payload, requirements);
        if (!verify.isValid()) {
            return SettleResponse.fail(
                    verify.invalidReason() != null ? verify.invalidReason() : "verification_failed",
                    verify.invalidMessage(), network);
        }
        String payer = verify.payer();
        String nonce = normalizedNonce(str(payload.payload(), "nonce"));

        // Step 3 — atomic, fenced claim
        UUID attemptId = UUID.randomUUID();
        SettlementRecord claim = new SettlementRecord(txHash, attemptId, digest, network,
                Status.CLAIMED, payer, requirements.payTo(), requirements.asset(),
                safeAmount(requirements.amount()), transferMethod(requirements), nonce, tx.ttlSlot(),
                clock.instant(), null, null, null, null, null, null);
        if (!repo.insertClaim(claim) && !repo.reclaim(claim, config.claimTtl())) {
            return SettleResponse.fail(ErrorCodes.DUPLICATE_SETTLEMENT, null, network);
        }

        // Step 4 — SUBMITTING persisted BEFORE any wire I/O
        if (!repo.casTransition(txHash, attemptId, Status.CLAIMED, Status.SUBMITTING, Map.of())) {
            return SettleResponse.fail(ErrorCodes.DUPLICATE_SETTLEMENT, null, network);
        }
        SubmissionResult submission = chain.submitTransaction(Base64.getDecoder().decode(txB64));
        switch (submission) {
            case SubmissionResult.Rejected rejected -> {
                repo.casTransition(txHash, attemptId, Status.SUBMITTING, Status.FAILED,
                        Map.of("error_reason", ErrorCodes.SETTLEMENT_FAILED));
                return SettleResponse.fail(ErrorCodes.SETTLEMENT_FAILED, rejected.cause(), network);
            }
            case SubmissionResult.NotSubmitted notSubmitted -> {
                // nothing broadcast — claim safely released for a legitimate retry
                repo.casTransition(txHash, attemptId, Status.SUBMITTING, Status.FAILED,
                        Map.of("error_reason", ErrorCodes.SETTLEMENT_FAILED));
                return SettleResponse.fail(ErrorCodes.SETTLEMENT_FAILED, notSubmitted.cause(), network);
            }
            case SubmissionResult.Unknown unknown -> {
                // possibly broadcast — row STAYS SUBMITTING; only the reconciler resolves it
                log.warn("submission outcome unknown for {}: {}", txHash, unknown.cause());
                return SettleResponse.failWithTx(ErrorCodes.SETTLEMENT_NOT_CONFIRMED,
                        txHash, network, payer, null);
            }
            case SubmissionResult.Accepted accepted -> {
                repo.casTransition(txHash, attemptId, Status.SUBMITTING, Status.SUBMITTED,
                        Map.of("submitted_at", clock.instant()));
            }
        }

        // accept-mempool fast path: treat node acceptance as settled without awaiting
        // the confirmation depth (faster, less safe — opt-in).
        if (config.acceptMempool()) {
            return SettleResponse.ok(txHash, network, payer, "mempool");
        }

        // Step 5 — await inclusion at the configured depth
        InclusionResult inclusion = chain.awaitInclusion(txHash, config.confirmationDepth(),
                config.confirmationTimeout());
        if (inclusion instanceof InclusionResult.Included included
                && included.depth() >= config.confirmationDepth()) {
            SettleResponse ok = SettleResponse.ok(txHash, network, payer, "confirmed");
            repo.casTransition(txHash, attemptId, Status.SUBMITTED, Status.CONFIRMED, Map.of(
                    "confirmed_at", clock.instant(),
                    "confirmed_slot", included.slot(),
                    "confirmed_block", included.blockHash(),
                    "response_json", toJson(ok)));
            return ok;
        }

        // Step 6 — not confirmed in time: row kept (NOT_CONFIRMED), no extra.status claim
        repo.casTransition(txHash, attemptId, Status.SUBMITTED, Status.NOT_CONFIRMED, Map.of());
        return SettleResponse.failWithTx(ErrorCodes.SETTLEMENT_NOT_CONFIRMED, txHash, network, payer, null);
    }

    /** @return a short-circuit response, or null to proceed with a fresh attempt. */
    private SettleResponse handleExisting(SettlementRecord rec, PaymentPayload payload,
                                          PaymentRequirements requirements, String digest, String network) {
        switch (rec.status()) {
            case CONFIRMED -> {
                if (!digest.equals(rec.requirementsDigest())) {
                    // different purchase reusing a settled tx: fall through — re-verification
                    // fails naturally on the spent nonce (nonce_not_on_chain)
                    return null;
                }
                if (!config.idempotentReplay()) {
                    return SettleResponse.fail(ErrorCodes.DUPLICATE_SETTLEMENT, null, network);
                }
                return replay(rec, payload, requirements, network);
            }
            case CLAIMED -> {
                boolean stale = rec.claimedAt().isBefore(clock.instant().minus(config.claimTtl()));
                return stale ? null : SettleResponse.fail(ErrorCodes.DUPLICATE_SETTLEMENT, null, network);
            }
            case SUBMITTING, SUBMITTED, NOT_CONFIRMED -> {
                InclusionResult inc;
                try {
                    inc = chain.checkInclusion(rec.txHash());
                } catch (ChainLookupException e) {
                    // lookup errors are never absence: preserve state, retryable failure
                    return SettleResponse.fail(ErrorCodes.CHAIN_LOOKUP_FAILED, e.getMessage(), network);
                }
                if (inc instanceof InclusionResult.Included included
                        && included.depth() >= config.confirmationDepth()) {
                    promote(rec, included, network);
                    SettlementRecord promoted = repo.find(rec.txHash()).orElse(rec);
                    return handleExisting(promoted, payload, requirements, digest, network);
                }
                return SettleResponse.fail(ErrorCodes.DUPLICATE_SETTLEMENT, null, network);
            }
            case EXPIRED -> {
                InclusionResult inc;
                try {
                    inc = chain.checkInclusion(rec.txHash());
                } catch (ChainLookupException e) {
                    return SettleResponse.fail(ErrorCodes.CHAIN_LOOKUP_FAILED, e.getMessage(), network);
                }
                if (inc instanceof InclusionResult.Included included
                        && included.depth() >= config.confirmationDepth()) {
                    promoteFrom(rec, Status.EXPIRED, included, network);
                    SettlementRecord promoted = repo.find(rec.txHash()).orElse(rec);
                    return handleExisting(promoted, payload, requirements, digest, network);
                }
                return null; // proceed — verification reports whatever the chain says
            }
            case FAILED -> {
                return null; // reclaimable
            }
        }
        return null;
    }

    /** Opt-in idempotent replay: never check-free, never chain-blind. */
    private SettleResponse replay(SettlementRecord rec, PaymentPayload payload,
                                  PaymentRequirements requirements, String network) {
        VerifyResponse profile = scheme.verifyReplayProfile(payload, requirements,
                rec.nonceOutref(), rec.payer());
        if (!profile.isValid()) {
            return SettleResponse.fail(profile.invalidReason(), profile.invalidMessage(), network);
        }
        if (rec.confirmedAt() != null
                && rec.confirmedAt().isAfter(clock.instant().minus(config.stabilityWindow()))) {
            InclusionResult inc;
            try {
                inc = chain.checkInclusion(rec.txHash());
            } catch (ChainLookupException e) {
                return SettleResponse.fail(ErrorCodes.CHAIN_LOOKUP_FAILED, e.getMessage(), network);
            }
            if (!(inc instanceof InclusionResult.Included included)
                    || included.depth() < config.confirmationDepth()) {
                // rollback discovered synchronously: fenced demotion, truthful response
                repo.casTransition(rec.txHash(), rec.attemptId(), Status.CONFIRMED, Status.SUBMITTED,
                        Map.of());
                return SettleResponse.failWithTx(ErrorCodes.SETTLEMENT_NOT_CONFIRMED,
                        rec.txHash(), network, rec.payer(), null);
            }
        }
        if (rec.responseJson() != null) {
            try {
                return ProtocolJson.mapper().readValue(rec.responseJson(), SettleResponse.class);
            } catch (Exception e) {
                log.warn("stored response unreadable for {}, rebuilding", rec.txHash());
            }
        }
        return SettleResponse.ok(rec.txHash(), network, rec.payer(), "confirmed");
    }

    private void promote(SettlementRecord rec, InclusionResult.Included included, String network) {
        promoteFrom(rec, rec.status(), included, network);
    }

    private void promoteFrom(SettlementRecord rec, Status from, InclusionResult.Included included,
                             String network) {
        SettleResponse ok = SettleResponse.ok(rec.txHash(), network, rec.payer(), "confirmed");
        repo.casTransition(rec.txHash(), rec.attemptId(), from, Status.CONFIRMED, Map.of(
                "confirmed_at", clock.instant(),
                "confirmed_slot", included.slot(),
                "confirmed_block", included.blockHash(),
                "response_json", toJson(ok)));
    }

    private static String toJson(SettleResponse response) {
        try {
            return ProtocolJson.mapper().writeValueAsString(response);
        } catch (Exception e) {
            return null;
        }
    }

    private static String transferMethod(PaymentRequirements requirements) {
        return requirements.extra() == null ? "default"
                : String.valueOf(requirements.extra().getOrDefault("assetTransferMethod", "default"));
    }

    private static BigDecimal safeAmount(String amount) {
        try {
            return new BigDecimal(amount);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String normalizedNonce(String nonce) {
        if (nonce == null) return null;
        int sep = nonce.indexOf('#');
        if (sep < 0) return nonce.toLowerCase();
        return nonce.substring(0, sep).toLowerCase() + "#" + Integer.parseInt(nonce.substring(sep + 1));
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v instanceof String s ? s : null;
    }
}
