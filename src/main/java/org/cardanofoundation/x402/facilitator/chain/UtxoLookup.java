package org.cardanofoundation.x402.facilitator.chain;

import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;

/** A single verification request's lookup session; use serially and never share between requests. */
@FunctionalInterface
public interface UtxoLookup {
    UtxoState getUtxoState(String txHashHex, int index);
}
