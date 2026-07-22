package org.cardanofoundation.x402.facilitator.chain.blockfrost;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.model.Block;
import com.bloxbean.cardano.client.backend.model.TransactionContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.model.chain.BackendHealth;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;

import java.time.Duration;
import java.util.List;

/**
 * Blockfrost backend: owns every chain capability for its network.
 * "Unspent" = the outref is present in its owning address's live UTxO set
 * (Blockfrost address UTxOs are unspent-only). 404 on the outref itself folds
 * into Spent() — consistent with "not in live set" semantics; Unknown() never
 * occurs on this backend. Submission is era-agnostic (raw CBOR over HTTP).
 */
@Log4j2
@RequiredArgsConstructor
public class BlockfrostChainService implements FacilitatorChainService {

    private final BackendService backend;
    private final Duration pollInterval;
    private volatile long lastProbeMillis;
    private volatile boolean lastProbeOk;

    @Override
    public UtxoState getUtxoState(String txHashHex, int index) {
        try {
            Result<Utxo> outputRes = backend.getUtxoService().getTxOutput(txHashHex, index);
            if (!outputRes.isSuccessful()) {
                if (outputRes.code() == 404) return new UtxoState.Spent(); // never existed = not in live set
                throw new ChainLookupException("Blockfrost getTxOutput failed: " + outputRes.getResponse());
            }
            String owner = outputRes.getValue().getAddress();
            for (int page = 1; ; page++) {
                Result<List<Utxo>> pageRes = backend.getUtxoService().getUtxos(owner, 100, page);
                if (!pageRes.isSuccessful()) {
                    if (pageRes.code() == 404) return new UtxoState.Spent(); // address has no UTxOs
                    throw new ChainLookupException("Blockfrost getUtxos failed: " + pageRes.getResponse());
                }
                List<Utxo> utxos = pageRes.getValue();
                if (utxos == null || utxos.isEmpty()) return new UtxoState.Spent();
                boolean present = utxos.stream().anyMatch(u ->
                        u.getTxHash().equalsIgnoreCase(txHashHex) && u.getOutputIndex() == index);
                if (present) return new UtxoState.Unspent(owner);
                if (utxos.size() < 100) return new UtxoState.Spent();
            }
        } catch (ChainLookupException e) {
            throw e;
        } catch (Exception e) {
            throw new ChainLookupException("Blockfrost lookup failed", e);
        }
    }

    @Override
    public long getCurrentSlot() {
        try {
            Result<Block> res = backend.getBlockService().getLatestBlock();
            if (!res.isSuccessful())
                throw new ChainLookupException("Blockfrost latest block: " + res.getResponse());
            return res.getValue().getSlot();
        } catch (ChainLookupException e) {
            throw e;
        } catch (Exception e) {
            throw new ChainLookupException("Blockfrost latest block failed", e);
        }
    }

    @Override
    public SubmissionResult submitTransaction(byte[] txBytes) {
        try {
            Result<String> res = backend.getTransactionService().submitTransaction(txBytes);
            if (res.isSuccessful()) {
                return new SubmissionResult.Accepted(res.getValue().toLowerCase());
            }
            // An HTTP-level error response from the provider is a definitive node/
            // provider verdict (Blockfrost surfaces node validation errors as 400).
            return new SubmissionResult.Rejected("Blockfrost submit rejected: " + res.getResponse());
        } catch (Exception e) {
            // Transport failure after the wire — the node may have accepted.
            return new SubmissionResult.Unknown("Blockfrost submit transport failure: " + e.getMessage());
        }
    }

    @Override
    public InclusionResult checkInclusion(String txHashHex) {
        try {
            Result<TransactionContent> res = backend.getTransactionService().getTransaction(txHashHex);
            if (!res.isSuccessful()) {
                if (res.code() == 404) return new InclusionResult.NotSeen();
                throw new ChainLookupException("Blockfrost getTransaction failed: " + res.getResponse());
            }
            TransactionContent tx = res.getValue();
            Result<Block> latest = backend.getBlockService().getLatestBlock();
            if (!latest.isSuccessful())
                throw new ChainLookupException("Blockfrost latest block: " + latest.getResponse());
            long depth = latest.getValue().getHeight() - tx.getBlockHeight() + 1;
            return new InclusionResult.Included((int) Math.max(depth, 1), tx.getSlot(), tx.getBlock());
        } catch (ChainLookupException e) {
            throw e;
        } catch (Exception e) {
            throw new ChainLookupException("Blockfrost inclusion lookup failed", e);
        }
    }

    @Override
    public InclusionResult awaitInclusion(String txHashHex, int minDepth, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        InclusionResult last = new InclusionResult.NotSeen();
        while (System.currentTimeMillis() < deadline) {
            try {
                last = checkInclusion(txHashHex);
                if (last instanceof InclusionResult.Included inc && inc.depth() >= minDepth) return last;
            } catch (ChainLookupException e) {
                log.debug("transient inclusion lookup failure for {}: {}", txHashHex, e.getMessage());
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    @Override
    public BackendHealth health() {
        long now = System.currentTimeMillis();
        if (now - lastProbeMillis < 30_000) {
            return lastProbeOk ? BackendHealth.ok() : BackendHealth.down("last Blockfrost probe failed");
        }
        try {
            getCurrentSlot();
            lastProbeOk = true;
        } catch (RuntimeException e) {
            lastProbeOk = false;
        }
        lastProbeMillis = now;
        return lastProbeOk ? BackendHealth.ok() : BackendHealth.down("Blockfrost unreachable or key invalid");
    }
}
