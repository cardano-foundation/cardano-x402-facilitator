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
                throw new ChainLookupException("Blockfrost params: " + res.getResponse());
            }
            com.bloxbean.cardano.client.api.model.ProtocolParams pp = res.getValue();
            ProtocolParams fresh = new ProtocolParams(
                    new BigInteger(pp.getCoinsPerUtxoSize()),
                    pp.getMaxTxSize() == null ? 16384 : pp.getMaxTxSize(),
                    pp.getMinFeeA() == null ? null : BigInteger.valueOf(pp.getMinFeeA()),
                    pp.getMinFeeB() == null ? null : BigInteger.valueOf(pp.getMinFeeB()));
            if (fresh.coinsPerUtxoByte().signum() <= 0 || fresh.maxTxSize() <= 0
                    || fresh.minFeeCoefficient() == null || fresh.minFeeConstant() == null
                    || fresh.minFeeCoefficient().signum() < 0 || fresh.minFeeConstant().signum() < 0)
                throw new ChainLookupException("Blockfrost returned incomplete protocol parameters");
            cached = fresh;
            fetchedAtMillis = System.currentTimeMillis();
            return fresh;
        } catch (ChainLookupException e) {
            throw e;
        } catch (Exception e) {
            throw new ChainLookupException("Blockfrost params failed", e);
        }
    }
}
