package org.cardanofoundation.x402.facilitator.service.settlement;

import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord.Status;
import org.cardanofoundation.x402.facilitator.model.protocol.SettleResponse;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.repository.SettlementRepository;
import org.cardanofoundation.x402.facilitator.repository.SettlementRepository.ReconcileCursor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reconciles against each verified selected policy. Legacy policy remains unknown until a
 * verified retry; no age horizon or provider outage authorizes expiry or resubmission.
 * PostgreSQL advisory locks serialize sweeps while row and policy fencing handles request races.
 */
@Log4j2
@RequiredArgsConstructor
public class SettlementReconciler {

    private static final long ADVISORY_LOCK_KEY = 0x402_CA8DA_0L;
    private static final long TTL_SAFETY_MARGIN_SLOTS = 120;

    private final SettlementRepository repo;
    private final Map<String, FacilitatorChainService> chainByNetwork;
    private final DataSource dataSource;
    private final int confirmationDepth;
    private final Duration stabilityWindow;
    private final Duration reconcileHorizon;
    private final Clock clock;
    private final boolean postgres;

    // Each instance resumes its own successful sweeps; lock contention never resets progress.
    // Restarting an instance restarts its scan. No journal state or expiry policy is changed.
    private ReconcileCursor cursor;

    /**
     * Advisory locks are SESSION-scoped: the lock must be taken and released on
     * one dedicated connection held open for the whole sweep — per-query pooled
     * connections would drop it immediately.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    public synchronized void sweep() {
        if (!postgres) {
            doSweep();
            return;
        }
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!tryLock(lockConnection)) return;
            try {
                doSweep();
            } finally {
                unlock(lockConnection);
            }
        } catch (SQLException e) {
            log.warn("reconciler lock connection failed: {}", e.getMessage());
        }
    }

    private void doSweep() {
        Instant confirmedAfter = clock.instant().minus(stabilityWindow);
        List<SettlementRecord> batch = repo.dueForReconcile(confirmedAfter, 200, cursor);
        if (batch.isEmpty() && cursor != null) {
            cursor = null;
            batch = repo.dueForReconcile(confirmedAfter, 200, null);
        }
        for (SettlementRecord rec : batch) {
            try {
                reconcile(rec);
            } catch (RuntimeException e) {
                log.warn("reconcile skipped for {}: {}", rec.txHash(), e.getMessage());
            } finally {
                // Unknown state, absent providers and failed observations must not block later rows.
                cursor = ReconcileCursor.after(rec);
            }
        }
    }

    private void reconcile(SettlementRecord rec) {
        FacilitatorChainService chain = chainByNetwork.get(rec.network());
        if (chain == null) return;
        InclusionResult inc;
        try {
            inc = chain.checkInclusion(rec.txHash());
        } catch (ChainLookupException e) {
            return; // preserve state; next sweep retries
        }
        Integer selected = rec.selectedConfirmations();
        boolean includedAtDepth = selected != null && inc instanceof InclusionResult.Included included
                && included.depth() >= Math.max(0, selected);

        if (includedAtDepth) {
            if (rec.status() == Status.CONFIRMED) return; // do not extend the stability window each sweep
            InclusionResult.Included included = (InclusionResult.Included) inc;
            SettleResponse ok = SettleResponse.confirmed(rec.txHash(), rec.network(), rec.payer(), included.depth());
            repo.recordObservation(rec, Status.CONFIRMED, Map.of(
                    "confirmed_at", clock.instant(), "confirmed_slot", included.slot(),
                    "confirmed_block", included.blockHash(), "response_json", SettlementService.json(ok)));
            return;
        }
        if (rec.status() == Status.CONFIRMED && selected != null) {
            repo.recordObservation(rec, Status.SUBMITTED, Map.of());
            return;
        }
        if (inc instanceof InclusionResult.Included included) {
            // Included below depth, or V1 policy still unknown: record evidence, never expire/promote.
            repo.recordObservation(rec, rec.status(), Map.of(
                    "confirmed_slot", included.slot(), "confirmed_block", included.blockHash()));
            return;
        }
        // Mempool observations are not absence. Neither age nor missing TTL proves a transaction dead.
        if (!(inc instanceof InclusionResult.NotSeen) || rec.txTtlSlot() == null) return;
        long currentSlot;
        try {
            currentSlot = chain.getCurrentSlot();
        } catch (ChainLookupException e) {
            return;
        }
        if (currentSlot > rec.txTtlSlot() && currentSlot - rec.txTtlSlot() > TTL_SAFETY_MARGIN_SLOTS) {
            repo.recordObservation(rec, Status.EXPIRED, Map.of());
        }
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT pg_try_advisory_lock(" + ADVISORY_LOCK_KEY + ")")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static void unlock(Connection connection) {
        try (Statement st = connection.createStatement()) {
            st.execute("SELECT pg_advisory_unlock(" + ADVISORY_LOCK_KEY + ")");
        } catch (SQLException e) {
            log.warn("advisory unlock failed: {}", e.getMessage());
        }
    }

}
