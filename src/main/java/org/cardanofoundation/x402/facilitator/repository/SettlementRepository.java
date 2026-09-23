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
            confirmed_at, confirmed_slot, confirmed_block, error_reason, response_json,
            selected_confirmations, submission_provenance, submission_accepted, terms_digest""";

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<SettlementRecord> find(String txHash) {
        List<SettlementRecord> rows = jdbc.query(
                "SELECT " + COLS + " FROM facilitator.settlement WHERE tx_hash = :h",
                Map.of("h", txHash), SettlementRepository::mapRow);
        return rows.stream().findFirst();
    }

    /** @return false on either transaction or Masumi terms conflict; the statement is atomic. */
    public boolean insertClaim(SettlementRecord r) {
        try {
            int n = jdbc.update("""
                    INSERT INTO facilitator.settlement (%s)
                    VALUES (:txHash, :attemptId, :digest, :network, :status, :payer, :payTo, :asset,
                            :amount, :method, :nonce, :ttl, :claimedAt, NULL, NULL, NULL, NULL, NULL, NULL,
                            :depth, :provenance, :accepted, :terms)
                    """.formatted(COLS), params(r));
            return n == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** Only a proven local pre-submit claim can be reclaimed. Legacy rows are observation-only. */
    public boolean reclaim(SettlementRecord fresh, Duration claimTtl) {
        MapSqlParameterSource p = params(fresh)
                .addValue("staleBefore", Timestamp.from(fresh.claimedAt().minus(claimTtl)));
        try {
            return jdbc.update("""
                    UPDATE facilitator.settlement
                       SET attempt_id = :attemptId, requirements_digest = :digest, payer = :payer,
                           pay_to = :payTo, asset = :asset, amount = :amount, transfer_method = :method,
                           nonce_outref = :nonce, tx_ttl_slot = :ttl, claimed_at = :claimedAt,
                           selected_confirmations = :depth, terms_digest = :terms
                     WHERE tx_hash = :txHash AND status = 'CLAIMED'
                       AND submission_provenance = 'LOCAL' AND submission_accepted = false
                       AND submitted_at IS NULL AND claimed_at < :staleBefore
                       AND (terms_digest IS NULL OR terms_digest = :terms)
                    """, p) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** Transaction and terms claims are released together only for a fenced, proven no-send outcome. */
    public boolean releaseUnsubmitted(String txHash, UUID attemptId) {
        return jdbc.update("""
                DELETE FROM facilitator.settlement WHERE tx_hash = :h AND attempt_id = :a
                  AND status = 'SUBMITTING' AND submission_provenance = 'LOCAL'
                  AND submission_accepted = false AND submitted_at IS NULL
                """, Map.of("h", txHash, "a", attemptId)) == 1;
    }

    /** A verified retry can supply missing history, and can strengthen but never weaken stored depth. */
    public boolean bindVerifiedRetry(SettlementRecord rec, int depth, String termsDigest) {
        return bindVerifiedRetry(rec, depth, termsDigest, rec.payer());
    }

    public boolean bindVerifiedRetry(SettlementRecord rec, int depth, String termsDigest, String verifiedPayer) {
        try {
            return jdbc.update("""
                    UPDATE facilitator.settlement
                       SET selected_confirmations = CASE
                             WHEN selected_confirmations IS NULL OR selected_confirmations < :depth
                             THEN :depth ELSE selected_confirmations END,
                           terms_digest = :terms, payer = :payer
                     WHERE tx_hash = :h AND attempt_id = :a
                       AND (terms_digest IS NULL OR terms_digest = :terms)
                    """, new MapSqlParameterSource().addValue("h", rec.txHash())
                    .addValue("a", rec.attemptId()).addValue("depth", depth).addValue("terms", termsDigest)
                    .addValue("payer", verifiedPayer)) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** Evidence transitions also fence on policy, preventing a concurrent stricter retry being overwritten. */
    public boolean recordObservation(SettlementRecord rec, SettlementRecord.Status to, Map<String, Object> sets) {
        return transition(rec.txHash(), rec.attemptId(), rec.status(), to, sets,
                rec.selectedConfirmations(), true);
    }

    /** Fenced CAS transition; extras may set submitted/confirmed columns. */
    public boolean casTransition(String txHash, UUID attemptId,
                                 SettlementRecord.Status from, SettlementRecord.Status to,
                                 Map<String, Object> extraSets) {
        return transition(txHash, attemptId, from, to, extraSets, null, false);
    }

    private boolean transition(String txHash, UUID attemptId, SettlementRecord.Status from,
                               SettlementRecord.Status to, Map<String, Object> extraSets,
                               Integer policy, boolean fencePolicy) {
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
        if (fencePolicy) {
            sql.append(" AND ((selected_confirmations IS NULL AND :policy IS NULL) OR selected_confirmations = :policy)");
            p.addValue("policy", policy, java.sql.Types.INTEGER);
        }
        return jdbc.update(sql.toString(), p) == 1;
    }

    /** Stable keyset position; tx_hash breaks ties between claims with the same timestamp. */
    public record ReconcileCursor(Instant claimedAt, String txHash) {
        public static ReconcileCursor after(SettlementRecord rec) {
            return new ReconcileCursor(rec.claimedAt(), rec.txHash());
        }
    }

    /** Non-terminal rows the reconciler owns, plus recent CONFIRMED for the stability re-check. */
    public List<SettlementRecord> dueForReconcile(Instant confirmedAfter, int limit) {
        return dueForReconcile(confirmedAfter, limit, null);
    }

    /** Continue a bounded scan without repeatedly selecting permanently pending older rows. */
    public List<SettlementRecord> dueForReconcile(Instant confirmedAfter, int limit, ReconcileCursor cursor) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("after", Timestamp.from(confirmedAfter)).addValue("limit", limit);
        String position = "";
        if (cursor != null) {
            position = " AND (claimed_at > :claimedAt OR (claimed_at = :claimedAt AND tx_hash > :txHash))";
            params.addValue("claimedAt", Timestamp.from(cursor.claimedAt())).addValue("txHash", cursor.txHash());
        }
        return jdbc.query("""
                SELECT %s FROM facilitator.settlement
                 WHERE (status IN ('SUBMITTING', 'SUBMITTED', 'NOT_CONFIRMED')
                    OR (submission_provenance = 'LEGACY' AND status IN ('CLAIMED', 'FAILED', 'EXPIRED'))
                    OR (status = 'CONFIRMED' AND (confirmed_at > :after OR selected_confirmations IS NULL)))
                %s
                 ORDER BY claimed_at, tx_hash
                 LIMIT :limit
                """.formatted(COLS, position), params, SettlementRepository::mapRow);
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
                .addValue("claimedAt", Timestamp.from(r.claimedAt()))
                .addValue("depth", r.selectedConfirmations())
                .addValue("provenance", r.submissionProvenance().name())
                .addValue("accepted", r.submissionAccepted())
                .addValue("terms", r.termsDigest());
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
                rs.getString("response_json"),
                (Integer) rs.getObject("selected_confirmations"),
                SettlementRecord.SubmissionProvenance.valueOf(rs.getString("submission_provenance")),
                rs.getBoolean("submission_accepted"),
                rs.getString("terms_digest"));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
