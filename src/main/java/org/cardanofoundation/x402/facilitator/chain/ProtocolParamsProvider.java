package org.cardanofoundation.x402.facilitator.chain;

import org.cardanofoundation.x402.facilitator.model.chain.ProtocolParams;

/** Live protocol parameters, cached with refresh — never cached forever. */
public interface ProtocolParamsProvider {

    /** Throws ChainLookupException when parameters cannot be (re)fetched. */
    ProtocolParams current();
}
