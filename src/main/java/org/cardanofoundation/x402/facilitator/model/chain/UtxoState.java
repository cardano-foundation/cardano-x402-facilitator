package org.cardanofoundation.x402.facilitator.model.chain;

/**
 * Tri-state UTxO answer: UNSPENT carries the owning address; SPENT folds
 * "spent" and "never existed" into one case (only the live UTxO set is
 * consulted); UNKNOWN is reserved for indexer sync-lag and must never be
 * interpreted as a deterministic verdict under the default `fail` policy.
 */
public sealed interface UtxoState {

    record Unspent(String ownerAddress) implements UtxoState {
    }

    /**
     * Spent, or never created. {@code ownerAddress} is the address that
     * controlled the output while it existed, and is null when the output was
     * never created at all.
     *
     * <p>It is reported because client submission needs it: the payment has
     * already consumed its own nonce by the time the facilitator sees it, so
     * the payer cannot be read from a live UTXO. The distinction also matters
     * on its own — an output that was spent is evidence of a real prior UTXO,
     * while one that never existed is not.
     */
    record Spent(String ownerAddress) implements UtxoState {
    }

    record Unknown() implements UtxoState {
    }
}
