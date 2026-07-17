package org.cardanofoundation.x402.facilitator.chain;

import org.cardanofoundation.x402.facilitator.model.chain.BackendHealth;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;

import java.time.Duration;

/**
 * Everything verify()/settle() needs from the Cardano chain (spec section 9.1).
 * One backend owns every capability for its network — no composite/failover.
 */
public interface FacilitatorChainService {

    /** Throws ChainLookupException on lookup failure or a stale backing view. */
    UtxoState getUtxoState(String txHashHex, int index);

    /** Throws ChainLookupException when the backing view is stale (fail-closed). */
    long getCurrentSlot();

    /**
     * Era resolution is backend-internal (blockfrost needs none; yaci resolves
     * tip-bounded per submission). Returns a classified outcome, never throws.
     */
    SubmissionResult submitTransaction(byte[] txBytes);

    /** One-shot. Throws ChainLookupException on lookup failure — error is never absence. */
    InclusionResult checkInclusion(String txHashHex);

    /**
     * Polls checkInclusion until depth >= minDepth or timeout. Transient lookup
     * errors are retried within the window; persistent failure = not confirmed
     * in time (state preserved by the caller — never demotion or release).
     */
    InclusionResult awaitInclusion(String txHashHex, int minDepth, Duration timeout);

    BackendHealth health();
}
