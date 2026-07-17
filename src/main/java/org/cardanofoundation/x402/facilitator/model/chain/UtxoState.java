package org.cardanofoundation.x402.facilitator.model.chain;

/**
 * Tri-state UTxO answer (spec section 9.1): UNSPENT carries the owning address;
 * SPENT folds "spent" and "never existed" (the reference's live-UTxO-set
 * semantics); UNKNOWN is reserved for the yaci sync-horizon case and must never
 * be interpreted as a deterministic verdict under the default `fail` policy.
 */
public sealed interface UtxoState {

    record Unspent(String ownerAddress) implements UtxoState {
    }

    record Spent() implements UtxoState {
    }

    record Unknown() implements UtxoState {
    }
}
