package org.cardanofoundation.x402.facilitator.model.chain;

/**
 * Classified submission outcome (spec section 9.1) — never a bare exception.
 * ACCEPTED: the backend confirmed acceptance (node verdict, or N2N announcement).
 * REJECTED: definitive node/provider validation failure (never occurs on the
 * light N2N path, which carries no verdict).
 * UNKNOWN: transport failure after the wire — the node may have accepted;
 * the claim must never be released on this outcome.
 * NOT_SUBMITTED: local failure BEFORE any wire I/O — nothing was broadcast,
 * the claim is safely released.
 */
public sealed interface SubmissionResult {

    record Accepted(String txHash) implements SubmissionResult {
    }

    record Rejected(String cause) implements SubmissionResult {
    }

    record Unknown(String cause) implements SubmissionResult {
    }

    record NotSubmitted(String cause) implements SubmissionResult {
    }
}
