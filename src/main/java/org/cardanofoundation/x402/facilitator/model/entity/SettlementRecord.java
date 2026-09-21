package org.cardanofoundation.x402.facilitator.model.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One row of facilitator.settlement. */
public record SettlementRecord(
        String txHash,
        UUID attemptId,
        String requirementsDigest,
        String network,
        Status status,
        String payer,
        String payTo,
        String asset,
        BigDecimal amount,
        String transferMethod,
        String nonceOutref,
        Long txTtlSlot,
        Instant claimedAt,
        Instant submittedAt,
        Instant confirmedAt,
        Long confirmedSlot,
        String confirmedBlock,
        String errorReason,
        String responseJson,
        Integer selectedConfirmations,
        SubmissionProvenance submissionProvenance,
        boolean submissionAccepted,
        String termsDigest) {

    public enum SubmissionProvenance { LEGACY, LOCAL }

    /** Compatibility constructor cannot invent provenance or historical policy. */
    public SettlementRecord(String txHash, UUID attemptId, String requirementsDigest, String network,
                            Status status, String payer, String payTo, String asset, BigDecimal amount,
                            String transferMethod, String nonceOutref, Long txTtlSlot, Instant claimedAt,
                            Instant submittedAt, Instant confirmedAt, Long confirmedSlot, String confirmedBlock,
                            String errorReason, String responseJson) {
        this(txHash, attemptId, requirementsDigest, network, status, payer, payTo, asset, amount,
                transferMethod, nonceOutref, txTtlSlot, claimedAt, submittedAt, confirmedAt, confirmedSlot,
                confirmedBlock, errorReason, responseJson, null, SubmissionProvenance.LEGACY, false, null);
    }

    public boolean isLegacy() { return submissionProvenance == SubmissionProvenance.LEGACY; }

    public enum Status {
        CLAIMED, SUBMITTING, SUBMITTED, NOT_CONFIRMED, CONFIRMED, FAILED, EXPIRED
    }
}
