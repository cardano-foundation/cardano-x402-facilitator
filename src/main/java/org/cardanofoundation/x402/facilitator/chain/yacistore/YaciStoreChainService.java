package org.cardanofoundation.x402.facilitator.chain.yacistore;

import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.model.chain.BackendHealth;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * yaci-store-backed chain service (spec section 9.3). Pure orchestration over
 * four small collaborators so it is fully unit-testable without a live node; the
 * yaci-store beans are adapted onto these in the profile-gated
 * {@code YaciStoreConfiguration}.
 *
 * <p>Tri-state UTxO (the subtle part): yaci-store keeps a spent output's row in
 * {@code address_utxo} (pruning is off by default), so presence there is NOT
 * "unspent" — the spend is recorded separately in {@code tx_input}. An outpoint
 * that is present and NOT in {@code tx_input} is {@link UtxoState.Unspent};
 * present-or-recorded-spent is {@link UtxoState.Spent}; absent-and-unspent is
 * {@link UtxoState.Unknown} while the indexer is behind the network tip (it may
 * simply not be indexed yet) and {@link UtxoState.Spent} (never existed) once the
 * indexer is caught up — unless the operator's unknown policy keeps it Unknown.
 */
public class YaciStoreChainService implements FacilitatorChainService {

    /** Output presence in {@code address_utxo} (spent OR unspent), with its owner + block. */
    public interface UtxoSource {
        Optional<Utxo> find(String txHash, int index);

        record Utxo(String ownerAddress, long block) {
        }
    }

    /** Whether an outpoint has been consumed (a row exists in {@code tx_input}). */
    public interface SpentChecker {
        boolean isSpent(String txHash, int index);
    }

    /** Locally-indexed tip position plus the network tip, for freshness/inclusion. */
    public interface ChainTip {
        OptionalLong currentSlot();

        long currentBlock();

        /**
         * The network's tip slot, or empty when it has never been successfully
         * observed. Empty must NOT be conflated with a real slot of 0 — a never-seen
         * network tip means freshness is unknown, so {@link #tipIsFresh()} fails open.
         */
        OptionalLong networkSlot();

        boolean available();
    }

    /** Era-correct transaction submission (N2C local socket or N2N relay). */
    public interface TxSubmitter {
        SubmissionResult submit(byte[] txBytes);
    }

    private final UtxoSource utxos;
    private final SpentChecker spentChecker;
    private final ChainTip tip;
    private final TxSubmitter submitter;
    private final long tipFreshnessSlots;
    private final boolean keepUnknownWhenStale;
    private final Duration pollInterval;
    private final Clock clock;

    public YaciStoreChainService(UtxoSource utxos, SpentChecker spentChecker, ChainTip tip,
                                 TxSubmitter submitter, long tipFreshnessSlots,
                                 boolean keepUnknownWhenStale, Duration pollInterval, Clock clock) {
        this.utxos = utxos;
        this.spentChecker = spentChecker;
        this.tip = tip;
        this.submitter = submitter;
        this.tipFreshnessSlots = tipFreshnessSlots;
        this.keepUnknownWhenStale = keepUnknownWhenStale;
        this.pollInterval = pollInterval;
        this.clock = clock;
    }

    @Override
    public UtxoState getUtxoState(String txHashHex, int index) {
        String txHash = txHashHex.toLowerCase();
        Optional<UtxoSource.Utxo> found = utxos.find(txHash, index);
        boolean spent = spentChecker.isSpent(txHash, index);
        if (found.isPresent() && !spent) return new UtxoState.Unspent(found.get().ownerAddress());
        if (spent) return new UtxoState.Spent();
        // Absent and unspent: not-yet-indexed vs never-existed depends on tip freshness.
        if (tipIsFresh()) return new UtxoState.Spent();
        return keepUnknownWhenStale ? new UtxoState.Unknown() : new UtxoState.Spent();
    }

    @Override
    public long getCurrentSlot() {
        OptionalLong slot = tip.currentSlot();
        if (slot.isEmpty()) throw new ChainLookupException("yaci-store has no cursor yet (indexer not started)");
        return slot.getAsLong();
    }

    @Override
    public SubmissionResult submitTransaction(byte[] txBytes) {
        return submitter.submit(txBytes);
    }

    @Override
    public InclusionResult checkInclusion(String txHashHex) {
        if (!tip.available()) throw new ChainLookupException("yaci-store cursor unavailable");
        // A confirmed tx produced output 0, which is indexed in address_utxo
        // (kept even once spent, pruning off). Its block gives the depth.
        Optional<UtxoSource.Utxo> out = utxos.find(txHashHex.toLowerCase(), 0);
        if (out.isEmpty()) return new InclusionResult.NotSeen();
        long depth = Math.max(1, tip.currentBlock() - out.get().block() + 1);
        long slot = tip.currentSlot().orElse(0);
        return new InclusionResult.Included((int) depth, slot, "");
    }

    @Override
    public InclusionResult awaitInclusion(String txHashHex, int minDepth, Duration timeout) {
        long deadline = clock.millis() + timeout.toMillis();
        InclusionResult last = new InclusionResult.NotSeen();
        while (true) {
            try {
                last = checkInclusion(txHashHex);
            } catch (ChainLookupException e) {
                last = new InclusionResult.NotSeen();
            }
            if (last instanceof InclusionResult.Included inc && inc.depth() >= minDepth) return last;
            if (clock.millis() >= deadline) return last;
            try {
                Thread.sleep(Math.min(pollInterval.toMillis(), Math.max(0, deadline - clock.millis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
    }

    @Override
    public BackendHealth health() {
        if (!tip.available() || tip.currentSlot().isEmpty()) {
            return BackendHealth.down("yaci-store indexer has no cursor");
        }
        return BackendHealth.ok();
    }

    private boolean tipIsFresh() {
        OptionalLong slot = tip.currentSlot();
        if (!tip.available() || slot.isEmpty()) return false;
        OptionalLong networkSlot = tip.networkSlot();
        // Network tip never observed -> we cannot prove the indexer is caught up.
        // Fail open (not fresh) so an absent output degrades to Unknown rather than
        // being wrongly declared Spent/never-existed.
        if (networkSlot.isEmpty()) return false;
        return networkSlot.getAsLong() - slot.getAsLong() <= tipFreshnessSlots;
    }
}
