package org.cardanofoundation.x402.facilitator.model.chain;

/** One-shot inclusion answer. Lookup errors THROW — an error is never absence. */
public sealed interface InclusionResult {

    record NotSeen() implements InclusionResult {
    }

    /**
     * A node holds the transaction but no block does. This is the {@code -1}
     * evidence level: the transaction passed validation to enter a mempool, so
     * it exists and is spendable, but it can still be dropped or reordered
     * away. Never treat it as settled unless the payment asked for {@code -1}.
     */
    record Mempool() implements InclusionResult {
    }

    /**
     * Included in a canonical block. {@code depth} counts blocks NEWER than the
     * one containing the transaction, matching the spec's `l1Confirmations`:
     * {@code 0} is inclusion, {@code n} is n newer blocks on top.
     */
    record Included(int depth, long slot, String blockHash) implements InclusionResult {
    }
}
