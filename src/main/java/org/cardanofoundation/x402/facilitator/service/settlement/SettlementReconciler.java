package org.cardanofoundation.x402.facilitator.service.settlement;

import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord.Status;
import org.cardanofoundation.x402.facilitator.model.protocol.ProtocolJson;
import org.cardanofoundation.x402.facilitator.model.protocol.SettleResponse;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.repository.SettlementRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Reconciler (spec section 8): sweeps SUBMITTING/SUBMITTED/NOT_CONFIRMED rows —
 * promote at depth, EXPIRE past the tx TTL (+margin) or, for TTL-less rows, past
 * the reconcile horizon — and re-checks recent CONFIRMED rows, demoting on
 * rollback. Lookup errors preserve state (error is never absence). Runs under a
 * Postgres advisory lock so only one instance sweeps at a time.
 */
public class SettlementReconciler {

    private static final Logger log = LogManager.getLogger(SettlementReconciler.class);
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

    public SettlementReconciler(SettlementRepository repo,
                                Map<String, FacilitatorChainService> chainByNetwork,
                                DataSource dataSource, int confirmationDepth,
                                Duration stabilityWindow, Duration reconcileHorizon,
                                Clock clock, boolean postgres) {
        this.repo = repo;
        this.chainByNetwork = chainByNetwork;
        this.dataSource = dataSource;
        this.confirmationDepth = confirmationDepth;
        this.stabilityWindow = stabilityWindow;
        this.reconcileHorizon = reconcileHorizon;
        this.clock = clock;
        this.postgres = postgres;
    }

    /**
     * Advisory locks are SESSION-scoped: the lock must be taken and released on
     * one dedicated connection held open for the whole sweep — per-query pooled
     * connections would drop it immediately.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    public void sweep() {
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
        for (SettlementRecord rec : repo.dueForReconcile(confirmedAfter, 200)) {
            try {
                reconcile(rec);
            } catch (RuntimeException e) {
                log.warn("reconcile skipped for {}: {}", rec.txHash(), e.getMessage());
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
        boolean includedAtDepth = inc instanceof InclusionResult.Included included
                && included.depth() >= confirmationDepth;

        if (rec.status() == Status.CONFIRMED) {
            if (!includedAtDepth) {
                // rollback: fenced demotion back to SUBMITTED; a later sweep re-resolves
                repo.casTransition(rec.txHash(), rec.attemptId(), Status.CONFIRMED, Status.SUBMITTED,
                        Map.of());
                log.warn("rollback detected: demoted {} from CONFIRMED", rec.txHash());
            }
            return;
        }

        if (includedAtDepth) {
            InclusionResult.Included included = (InclusionResult.Included) inc;
            SettleResponse ok = SettleResponse.ok(rec.txHash(), rec.network(), rec.payer(), "confirmed");
            repo.casTransition(rec.txHash(), rec.attemptId(), rec.status(), Status.CONFIRMED, Map.of(
                    "confirmed_at", clock.instant(),
                    "confirmed_slot", included.slot(),
                    "confirmed_block", included.blockHash(),
                    "response_json", toJson(ok)));
            return;
        }

        // not included: can it provably never land?
        if (rec.txTtlSlot() != null) {
            long currentSlot;
            try {
                currentSlot = chain.getCurrentSlot();
            } catch (ChainLookupException e) {
                return;
            }
            if (currentSlot > rec.txTtlSlot() + TTL_SAFETY_MARGIN_SLOTS) {
                repo.casTransition(rec.txHash(), rec.attemptId(), rec.status(), Status.EXPIRED, Map.of());
            }
        } else if (rec.claimedAt().isBefore(clock.instant().minus(reconcileHorizon))) {
            // TTL-less: nothing ever proves it dead — horizon-expire, with the
            // settlement path's EXPIRED-branch re-check as the safety net
            repo.casTransition(rec.txHash(), rec.attemptId(), rec.status(), Status.EXPIRED, Map.of());
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

    private static String toJson(SettleResponse response) {
        try {
            return ProtocolJson.mapper().writeValueAsString(response);
        } catch (Exception e) {
            return null;
        }
    }
}
