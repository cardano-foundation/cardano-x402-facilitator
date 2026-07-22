package org.cardanofoundation.x402.facilitator.chain.blockfrost;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import lombok.RequiredArgsConstructor;
import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.chain.ProtocolParamsProvider;
import org.cardanofoundation.x402.facilitator.model.chain.ProtocolParams;

import java.math.BigInteger;
import java.time.Duration;

/**
 * Live protocol params via /epochs/latest/parameters, cached with a 15-minute
 * TTL (governance-settable values must never be cached forever).
 */
@RequiredArgsConstructor
public class BlockfrostProtocolParamsProvider implements ProtocolParamsProvider {

    private static final Duration TTL = Duration.ofMinutes(15);

    private final BackendService backend;
    private volatile ProtocolParams cached;
    private volatile long fetchedAtMillis;

    @Override
    public ProtocolParams current() {
        ProtocolParams local = cached;
        if (local != null && System.currentTimeMillis() - fetchedAtMillis < TTL.toMillis()) {
            return local;
        }
        try {
            Result<com.bloxbean.cardano.client.api.model.ProtocolParams> res = backend.getEpochService().getProtocolParameters();
            if (!res.isSuccessful()) {
                if (local != null) return local; // serve slightly stale over failing
                throw new ChainLookupException("Blockfrost params: " + res.getResponse());
            }
            com.bloxbean.cardano.client.api.model.ProtocolParams pp = res.getValue();
            ProtocolParams fresh = new ProtocolParams(
                    new BigInteger(pp.getCoinsPerUtxoSize()),
                    pp.getMaxTxSize() == null ? 16384 : pp.getMaxTxSize());
            cached = fresh;
            fetchedAtMillis = System.currentTimeMillis();
            return fresh;
        } catch (ChainLookupException e) {
            throw e;
        } catch (Exception e) {
            if (local != null) return local;
            throw new ChainLookupException("Blockfrost params failed", e);
        }
    }
}
