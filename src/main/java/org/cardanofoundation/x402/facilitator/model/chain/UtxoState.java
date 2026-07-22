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

    record Spent() implements UtxoState {
    }

    record Unknown() implements UtxoState {
    }
}
