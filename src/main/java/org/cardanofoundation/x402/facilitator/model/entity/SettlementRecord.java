package org.cardanofoundation.x402.facilitator.model.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One row of facilitator.settlement (spec section 8). */
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
        String responseJson) {

    public enum Status {
        CLAIMED, SUBMITTING, SUBMITTED, NOT_CONFIRMED, CONFIRMED, FAILED, EXPIRED
    }
}
