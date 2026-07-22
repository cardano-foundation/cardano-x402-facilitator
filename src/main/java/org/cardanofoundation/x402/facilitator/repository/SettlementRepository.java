package org.cardanofoundation.x402.facilitator.repository;

import lombok.RequiredArgsConstructor;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Journal DAO. Every transition is a fenced compare-and-set on (tx_hash,
 * attempt_id, expected_status) so a suspended worker resuming after its claim
 * was reclaimed can never overwrite the new attempt.
 */
@Repository
@RequiredArgsConstructor
public class SettlementRepository {

    private static final String COLS = """
            tx_hash, attempt_id, requirements_digest, network, status, payer, pay_to, asset,
            amount, transfer_method, nonce_outref, tx_ttl_slot, claimed_at, submitted_at,
            confirmed_at, confirmed_slot, confirmed_block, error_reason, response_json""";

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<SettlementRecord> find(String txHash) {
        List<SettlementRecord> rows = jdbc.query(
                "SELECT " + COLS + " FROM facilitator.settlement WHERE tx_hash = :h",
                Map.of("h", txHash), SettlementRepository::mapRow);
        return rows.stream().findFirst();
    }

    /** @return false when a row for this tx hash already exists (claim conflict). */
    public boolean insertClaim(SettlementRecord r) {
        try {
            int n = jdbc.update("""
                    INSERT INTO facilitator.settlement (%s)
                    VALUES (:txHash, :attemptId, :digest, :network, :status, :payer, :payTo, :asset,
                            :amount, :method, :nonce, :ttl, :claimedAt, NULL, NULL, NULL, NULL, NULL, NULL)
                    """.formatted(COLS), params(r));
            return n == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * Atomically re-claims a row whose prior attempt is over: FAILED/EXPIRED
     * terminal rows, or a CLAIMED row older than the claim TTL (an attempt that
     * died in the claim->submit window; SUBMITTING/SUBMITTED/NOT_CONFIRMED are
     * never time-reclaimed — only the reconciler resolves them).
     */
    public boolean reclaim(SettlementRecord fresh, Duration claimTtl) {
        MapSqlParameterSource p = params(fresh)
                .addValue("staleBefore", Timestamp.from(fresh.claimedAt().minus(claimTtl)));
        int n = jdbc.update("""
                UPDATE facilitator.settlement
                   SET attempt_id = :attemptId, requirements_digest = :digest, status = :status,
                       payer = :payer, pay_to = :payTo, asset = :asset, amount = :amount,
                       transfer_method = :method, nonce_outref = :nonce, tx_ttl_slot = :ttl,
                       claimed_at = :claimedAt, submitted_at = NULL, confirmed_at = NULL,
                       confirmed_slot = NULL, confirmed_block = NULL, error_reason = NULL,
                       response_json = NULL
                 WHERE tx_hash = :txHash
                   AND (status IN ('FAILED', 'EXPIRED')
                        OR (status = 'CLAIMED' AND claimed_at < :staleBefore))
                """, p);
        return n == 1;
    }

    /** Fenced CAS transition; extras may set submitted/confirmed columns. */
    public boolean casTransition(String txHash, UUID attemptId,
                                 SettlementRecord.Status from, SettlementRecord.Status to,
                                 Map<String, Object> extraSets) {
        StringBuilder sql = new StringBuilder("UPDATE facilitator.settlement SET status = :to");
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("to", to.name())
                .addValue("h", txHash)
                .addValue("a", attemptId)
                .addValue("from", from.name());
        int i = 0;
        for (Map.Entry<String, Object> e : extraSets.entrySet()) {
            String param = "x" + (i++);
            sql.append(", ").append(e.getKey()).append(" = :").append(param);
            Object v = e.getValue();
            if (v instanceof Instant instant) v = Timestamp.from(instant);
            p.addValue(param, v);
        }
        sql.append(" WHERE tx_hash = :h AND attempt_id = :a AND status = :from");
        return jdbc.update(sql.toString(), p) == 1;
    }

    /** Non-terminal rows the reconciler owns, plus recent CONFIRMED for the stability re-check. */
    public List<SettlementRecord> dueForReconcile(Instant confirmedAfter, int limit) {
        return jdbc.query("""
                SELECT %s FROM facilitator.settlement
                 WHERE status IN ('SUBMITTING', 'SUBMITTED', 'NOT_CONFIRMED')
                    OR (status = 'CONFIRMED' AND confirmed_at > :after)
                 ORDER BY claimed_at
                 LIMIT :limit
                """.formatted(COLS),
                new MapSqlParameterSource()
                        .addValue("after", Timestamp.from(confirmedAfter))
                        .addValue("limit", limit),
                SettlementRepository::mapRow);
    }

    private static MapSqlParameterSource params(SettlementRecord r) {
        return new MapSqlParameterSource()
                .addValue("txHash", r.txHash())
                .addValue("attemptId", r.attemptId())
                .addValue("digest", r.requirementsDigest())
                .addValue("network", r.network())
                .addValue("status", r.status().name())
                .addValue("payer", r.payer())
                .addValue("payTo", r.payTo())
                .addValue("asset", r.asset())
                .addValue("amount", r.amount())
                .addValue("method", r.transferMethod())
                .addValue("nonce", r.nonceOutref())
                .addValue("ttl", r.txTtlSlot())
                .addValue("claimedAt", Timestamp.from(r.claimedAt()));
    }

    private static SettlementRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SettlementRecord(
                rs.getString("tx_hash"),
                rs.getObject("attempt_id", UUID.class),
                rs.getString("requirements_digest"),
                rs.getString("network"),
                SettlementRecord.Status.valueOf(rs.getString("status")),
                rs.getString("payer"),
                rs.getString("pay_to"),
                rs.getString("asset"),
                rs.getBigDecimal("amount"),
                rs.getString("transfer_method"),
                rs.getString("nonce_outref"),
                (Long) rs.getObject("tx_ttl_slot"),
                instant(rs.getTimestamp("claimed_at")),
                instant(rs.getTimestamp("submitted_at")),
                instant(rs.getTimestamp("confirmed_at")),
                (Long) rs.getObject("confirmed_slot"),
                rs.getString("confirmed_block"),
                rs.getString("error_reason"),
                rs.getString("response_json"));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
