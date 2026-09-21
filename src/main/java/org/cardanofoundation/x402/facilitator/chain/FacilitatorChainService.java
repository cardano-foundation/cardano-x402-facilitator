package org.cardanofoundation.x402.facilitator.chain;

import org.cardanofoundation.x402.facilitator.model.chain.BackendHealth;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;

import java.time.Duration;

/**
 * Everything verify()/settle() needs from the Cardano chain. One backend owns
 * every capability for its network — no composite/failover.
 */
public interface FacilitatorChainService {

    /** Throws ChainLookupException on lookup failure or a stale backing view. */
    UtxoState getUtxoState(String txHashHex, int index);

    /**
     * Opens one bounded session for all inputs of a verification request. Custom backends retain
     * compatibility through this default: each getUtxoState invocation consumes one call. Backends
     * that perform multiple external calls must override this method to account for each call.
     */
    default UtxoLookup openUtxoLookup() {
        var budget = new LookupBudget();
        java.util.Map<String, UtxoState> cache = new java.util.HashMap<>();
        return (hash, index) -> {
            try {
                budget.checkDeadline();
                String key = hash.toLowerCase(java.util.Locale.ROOT) + "#" + index;
                return cache.computeIfAbsent(key, ignored -> budget.call(() -> getUtxoState(hash, index)));
            } catch (LookupBudget.Exhausted e) {
                return new UtxoState.Unknown();
            }
        };
    }

    /** Current wall-clock slot in the network's era configuration, not the latest block's slot. */
    long getCurrentSlot();

    /** Submits the raw signed tx; returns a classified outcome, never throws. */
    SubmissionResult submitTransaction(byte[] txBytes);

    /**
     * One-shot authenticated evidence: Included MUST prove the exact transaction was phase-2
     * valid and created its outputs. A block appearance alone is insufficient because is_valid
     * is not covered by the transaction id. Unknown validity fails closed with ChainLookupException.
     * Throws on lookup failure — error is never absence.
     */
    InclusionResult checkInclusion(String txHashHex);

    /**
     * Polls checkInclusion until depth >= minDepth or timeout. Transient lookup
     * errors are retried within the window; persistent failure = not confirmed
     * in time (state preserved by the caller — never demotion or release).
     */
    InclusionResult awaitInclusion(String txHashHex, int minDepth, Duration timeout);

    BackendHealth health();
}
