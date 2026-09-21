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
import org.cardanofoundation.x402.facilitator.chain.NetworkClock;
import org.cardanofoundation.x402.facilitator.model.chain.BackendHealth;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.math.BigInteger;
import java.util.Map;
import java.util.HashMap;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import java.util.List;
import java.util.regex.Pattern;

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
    /** Base URL and key for the two queries the backend interface does not expose. */
    private final String baseUrl;
    private final String projectId;
    private final NetworkClock networkClock;
    private final Clock wallClock;
    private volatile long lastProbeMillis;
    private volatile boolean lastProbeOk;

    private static final Pattern TX_HASH = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Duration MEMPOOL_TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Legacy construction for chain I/O; validity-slot queries require the network-clock overload. */
    public BlockfrostChainService(BackendService backend, Duration pollInterval, String baseUrl, String projectId) {
        this(backend, pollInterval, baseUrl, projectId, null, Clock.systemUTC());
    }

    @Override
    public UtxoState getUtxoState(String txHashHex, int index) {
        try {
            Result<Utxo> outputRes = backend.getUtxoService().getTxOutput(txHashHex, index);
            if (!outputRes.isSuccessful()) {
                // Never created: no owner to report.
                if (outputRes.code() == 404) return new UtxoState.Spent(null);
                throw new ChainLookupException("Blockfrost getTxOutput failed: " + outputRes.getResponse());
            }
            // The producing transaction names the owner whether or not the output
            // still exists, which is what lets client-submitted payments resolve a
            // payer from a nonce their own transaction already consumed.
            String owner = outputRes.getValue().getAddress();
            for (int page = 1; ; page++) {
                Result<List<Utxo>> pageRes = backend.getUtxoService().getUtxos(owner, 100, page);
                if (!pageRes.isSuccessful()) {
                    if (pageRes.code() == 404) return new UtxoState.Spent(owner); // address has no UTxOs
                    throw new ChainLookupException("Blockfrost getUtxos failed: " + pageRes.getResponse());
                }
                List<Utxo> utxos = pageRes.getValue();
                if (utxos == null || utxos.isEmpty()) return new UtxoState.Spent(owner);
                boolean present = utxos.stream().anyMatch(u ->
                        u.getTxHash().equalsIgnoreCase(txHashHex) && u.getOutputIndex() == index);
                if (present) return snapshot(outputRes.getValue());
                if (page >= 1000) return new UtxoState.Unknown();
                if (utxos.size() < 100) return new UtxoState.Spent(owner);
            }
        } catch (ChainLookupException e) {
            throw e;
        } catch (Exception e) {
            throw new ChainLookupException("Blockfrost lookup failed", e);
        }
    }

    @Override
    public long getCurrentSlot() {
        if (networkClock == null) throw new ChainLookupException("current slot requires a configured network clock");
        // Slots advance even when no block is produced. The SDK builds TTL from
        // wall time, so the latest block's slot is not a valid 'now' for its limit.
        return networkClock.expectedSlotAt(wallClock.instant());
    }

    private Block latestBlock() {
        try {
            Result<Block> res = backend.getBlockService().getLatestBlock();
            if (!res.isSuccessful() || res.getValue() == null)
                throw new ChainLookupException("Blockfrost latest block: " + res.getResponse());
            return res.getValue();
        } catch (ChainLookupException e) {
            throw e;
        } catch (Exception e) {
            throw new ChainLookupException("Blockfrost latest block failed", e);
        }
    }

    private static UtxoState.Unspent snapshot(Utxo output) {
        BigInteger coin = null;
        Map<String, BigInteger> assets = new HashMap<>();
        if (output.getAmount() != null) for (var amount : output.getAmount()) {
            String unit = amount.getUnit();
            BigInteger quantity = amount.getQuantity();
            if (unit == null || quantity == null || quantity.signum() < 0)
                throw new ChainLookupException("invalid provider UTxO value");
            if (unit.equals("lovelace")) {
                if (coin != null) throw new ChainLookupException("duplicate lovelace in provider value");
                coin = quantity;
            } else {
                if (!unit.matches("[0-9a-fA-F]{56}(?:[0-9a-fA-F]{2}){0,32}"))
                    throw new ChainLookupException("invalid provider asset unit");
                String canonical = (unit.substring(0,56)+"."+unit.substring(56)).toLowerCase(java.util.Locale.ROOT);
                if (assets.put(canonical,quantity) != null)
                    throw new ChainLookupException("duplicate provider asset unit");
            }
        }
        return new UtxoState.Unspent(output.getAddress(), coin, assets);
    }

    @Override
    public SubmissionResult submitTransaction(byte[] txBytes) {
        String expectedHash;
        try { expectedHash = TransactionUtil.getTxHash(txBytes).toLowerCase(java.util.Locale.ROOT); }
        catch (RuntimeException e) { return new SubmissionResult.NotSubmitted("transaction cannot be hashed"); }
        try {
            Result<String> res = backend.getTransactionService().submitTransaction(txBytes);
            if (res.isSuccessful()) {
                String hash = res.getValue();
                if (hash == null || !TX_HASH.matcher(hash).matches() || !hash.equalsIgnoreCase(expectedHash))
                    return new SubmissionResult.Unknown("provider returned a missing or mismatched transaction hash");
                return new SubmissionResult.Accepted(expectedHash);
            }
            String response = res.getResponse() == null ? "" : res.getResponse();
            // Only explicit ledger validation verdicts prove this submission was rejected.
            // HTTP quota/authentication/gateway failures may follow an accepted wire submission.
            if (res.code() == 400 && response.matches("(?s).*\\b(?:ApplyTxError|ShelleyTxValidationError|ConwayUtxowFailure|"
                    + "ValueNotConservedUTxO|BadInputsUTxO|FeeTooSmallUTxO|OutsideValidityIntervalUTxO|"
                    + "MissingVKeyWitnessesUTXOW|ScriptWitnessNotValidatingUTXOW|ValidationTagMismatch)\\b.*"))
                return new SubmissionResult.Rejected("Blockfrost ledger rejection: " + response);
            return new SubmissionResult.Unknown("Blockfrost submit outcome uncertain: " + response);
        } catch (Exception e) {
            return new SubmissionResult.Unknown("Blockfrost submit transport failure: " + e.getMessage());
        }
    }

    @Override
    public InclusionResult checkInclusion(String txHashHex) {
        try {
            Result<TransactionContent> res = backend.getTransactionService().getTransaction(txHashHex);
            if (!res.isSuccessful()) {
                // Not in a block. It may still be in a mempool, which is the
                // `-1` evidence level and the only thing a just-broadcast
                // client-submitted payment can offer.
                if (res.code() == 404) return inMempool(txHashHex)
                        ? new InclusionResult.Mempool()
                        : new InclusionResult.NotSeen();
                throw new ChainLookupException("Blockfrost getTransaction failed: " + res.getResponse());
            }
            TransactionContent tx = res.getValue();
            if (tx == null || tx.getHash() == null || !tx.getHash().equalsIgnoreCase(txHashHex)
                    || !Boolean.TRUE.equals(tx.getValidContract()))
                throw new ChainLookupException("transaction receipt does not authenticate valid payment outputs");
            Result<Block> latest = backend.getBlockService().getLatestBlock();
            if (!latest.isSuccessful())
                throw new ChainLookupException("Blockfrost latest block: " + latest.getResponse());
            // `l1Confirmations` counts blocks NEWER than the containing block, so
            // a transaction in the tip has depth 0 ("canonical inclusion"), not 1.
            long depth = latest.getValue().getHeight() - tx.getBlockHeight();
            if (depth < 0) throw new ChainLookupException("transaction receipt is ahead of canonical tip");
            return new InclusionResult.Included((int) Math.max(depth, 0), tx.getSlot(), tx.getBlock());
        } catch (ChainLookupException e) {
            throw e;
        } catch (Exception e) {
            throw new ChainLookupException("Blockfrost inclusion lookup failed", e);
        }
    }

    /**
     * Whether a node is holding this transaction in its mempool.
     *
     * <p>Not on the backend interface, so it goes over raw HTTP. A provider
     * fault answers "no" rather than throwing: mempool presence only ever
     * strengthens the evidence, and an outage must not turn a confirmed payment
     * into a lookup failure.
     *
     * @param txHashHex the transaction id.
     * @return true when the provider reports it pending.
     */
    private boolean inMempool(String txHashHex) {
        if (baseUrl == null || !TX_HASH.matcher(txHashHex).matches()) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "mempool/" + txHashHex.toLowerCase()))
                    .header("project_id", projectId == null ? "" : projectId)
                    .timeout(MEMPOOL_TIMEOUT)
                    .GET()
                    .build();
            return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("mempool lookup unavailable for {}: {}", txHashHex, e.getMessage());
            return false;
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
                // Mempool acceptance satisfies -1 and nothing stronger.
                if (last instanceof InclusionResult.Mempool && minDepth <= -1) return last;
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
            latestBlock();
            lastProbeOk = true;
        } catch (RuntimeException e) {
            lastProbeOk = false;
        }
        lastProbeMillis = now;
        return lastProbeOk ? BackendHealth.ok() : BackendHealth.down("Blockfrost unreachable or key invalid");
    }
}
