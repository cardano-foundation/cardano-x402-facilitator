package org.cardanofoundation.x402.facilitator.model.chain;

/** One-shot inclusion answer. Lookup errors THROW — an error is never absence. */
public sealed interface InclusionResult {

    record NotSeen() implements InclusionResult {
    }

    record Included(int depth, long slot, String blockHash) implements InclusionResult {
    }
}
