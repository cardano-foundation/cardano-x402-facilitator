package org.cardanofoundation.x402.facilitator.testutil;

import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.chain.ProtocolParamsProvider;
import org.cardanofoundation.x402.facilitator.model.chain.BackendHealth;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.model.chain.ProtocolParams;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;

import java.math.BigInteger;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory chain-service test double for the new tri-state/classified SPI.
 * Mutable fields let scheme/settlement tests script every scenario without a
 * real chain: unknown/spent inputs, lookup failures, submission outcomes,
 * inclusion depth.
 */
public class FakeChainService implements FacilitatorChainService, ProtocolParamsProvider {

    /** key "txhash#index" -> owner address; absent key = Spent. */
    public final Map<String, String> unspent = new HashMap<>();
    public final Map<String, UtxoState> overrides = new HashMap<>();
    /** per-hash inclusion depth override; falls back to includedDepth. */
    public final Map<String, Integer> inclusionDepthByHash = new HashMap<>();
    public long currentSlot = 500_000L;
    public BigInteger coinsPerUtxoByte = BigInteger.valueOf(4310);
    public int maxTxSize = 16384;
    public boolean throwOnLookup = false;
    public boolean throwOnParams = false;
    public boolean throwOnInclusionCheck = false;
    public SubmissionResult submissionResult; // null => Accepted(computed hash)
    public int includedDepth = 1;             // <= 0 => NotSeen
    public String submittedTxHash;
    public int submitCount = 0;

    @Override
    public UtxoState getUtxoState(String txHashHex, int index) {
        if (throwOnLookup) throw new ChainLookupException("provider down");
        String key = txHashHex.toLowerCase() + "#" + index;
        UtxoState override = overrides.get(key);
        if (override != null) return override;
        String owner = unspent.get(key);
        return owner == null ? new UtxoState.Spent() : new UtxoState.Unspent(owner);
    }

    @Override
    public long getCurrentSlot() {
        if (throwOnLookup) throw new ChainLookupException("provider down");
        return currentSlot;
    }

    @Override
    public SubmissionResult submitTransaction(byte[] txBytes) {
        submitCount++;
        if (submissionResult != null) return submissionResult;
        submittedTxHash = TransactionUtil.getTxHash(txBytes).toLowerCase();
        return new SubmissionResult.Accepted(submittedTxHash);
    }

    @Override
    public InclusionResult checkInclusion(String txHashHex) {
        if (throwOnInclusionCheck) throw new ChainLookupException("inclusion lookup down");
        int depth = inclusionDepthByHash.getOrDefault(txHashHex.toLowerCase(), includedDepth);
        return depth <= 0 ? new InclusionResult.NotSeen()
                : new InclusionResult.Included(depth, currentSlot, "blockhash");
    }

    @Override
    public InclusionResult awaitInclusion(String txHashHex, int minDepth, Duration timeout) {
        try {
            InclusionResult r = checkInclusion(txHashHex);
            if (r instanceof InclusionResult.Included inc && inc.depth() >= minDepth) return r;
            return new InclusionResult.NotSeen();
        } catch (ChainLookupException e) {
            return new InclusionResult.NotSeen();
        }
    }

    @Override
    public BackendHealth health() {
        return throwOnLookup ? BackendHealth.down("provider down") : BackendHealth.ok();
    }

    @Override
    public ProtocolParams current() {
        if (throwOnParams) throw new ChainLookupException("params down");
        return new ProtocolParams(coinsPerUtxoByte, maxTxSize);
    }
}
