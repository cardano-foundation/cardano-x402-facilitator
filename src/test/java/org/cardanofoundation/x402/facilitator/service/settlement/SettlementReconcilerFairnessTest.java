package org.cardanofoundation.x402.facilitator.service.settlement;

import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.chain.FacilitatorChainService;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.model.entity.SettlementRecord;
import org.cardanofoundation.x402.facilitator.repository.SettlementRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SettlementReconcilerFairnessTest {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private DriverManagerDataSource dataSource;
    private SettlementRepository repo;
    private FacilitatorChainService chain;

    @BeforeEach void setup() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:fairness_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("facilitator").defaultSchema("facilitator").createSchemas(true).load().migrate();
        repo = new SettlementRepository(new NamedParameterJdbcTemplate(dataSource));
        chain = mock(FacilitatorChainService.class);
        when(chain.checkInclusion(anyString())).thenReturn(new InclusionResult.NotSeen());
    }

    @Test void permanentlyPendingFirstBatchDoesNotStarveLaterIncludedPayment() {
        for (int i = 0; i <= 200; i++) insert(i, NOW.minusSeconds(1000 - i));
        when(chain.checkInclusion(hash(200))).thenReturn(new InclusionResult.Included(10, 123L, "cd".repeat(32)));
        SettlementReconciler reconciler = reconciler();
        reconciler.sweep();
        verify(chain, times(200)).checkInclusion(anyString());
        verify(chain, never()).checkInclusion(hash(200));
        reconciler.sweep();
        assertThat(repo.find(hash(200)).orElseThrow().status()).isEqualTo(SettlementRecord.Status.CONFIRMED);
        assertThat(repo.find(hash(0)).orElseThrow().status()).isEqualTo(SettlementRecord.Status.SUBMITTED);
        verify(chain, never()).getCurrentSlot(); // missing TTL is not expiry evidence
    }

    @Test void tiedClaimTimesAdvancePastFailuresAndWrapToRevisitEarlierRows() {
        // Reverse insertion order ensures ordering comes from the query, including its tie breaker.
        for (int i = 400; i >= 0; i--) insert(i, NOW.minusSeconds(1000));
        when(chain.checkInclusion(hash(0))).thenThrow(new ChainLookupException("provider unavailable"));
        when(chain.checkInclusion(hash(199))).thenThrow(new IllegalStateException("bad provider response"));
        SettlementReconciler reconciler = reconciler();
        reconciler.sweep();
        reconciler.sweep();
        reconciler.sweep();
        for (int i = 0; i <= 400; i++) verify(chain).checkInclusion(hash(i));
        verify(chain, times(401)).checkInclusion(anyString());
        doReturn(new InclusionResult.Included(10, 123L, "cd".repeat(32))).when(chain).checkInclusion(hash(0));
        reconciler.sweep();
        assertThat(repo.find(hash(0)).orElseThrow().status()).isEqualTo(SettlementRecord.Status.CONFIRMED);
        assertThat(repo.find(hash(199)).orElseThrow().status()).isEqualTo(SettlementRecord.Status.SUBMITTED);
    }

    @Test void independentInstancesEachAdvanceWithoutSharingCursor() {
        for (int i = 0; i <= 200; i++) insert(i, NOW.minusSeconds(1000));
        SettlementReconciler first = reconciler();
        SettlementReconciler second = reconciler();
        first.sweep();
        second.sweep();
        first.sweep();
        second.sweep();
        verify(chain, times(2)).checkInclusion(hash(200));
        verify(chain, times(402)).checkInclusion(anyString());
    }

    private SettlementReconciler reconciler() {
        return new SettlementReconciler(repo, Map.of("cardano:preprod", chain), dataSource, 1,
                Duration.ofHours(1), Duration.ofHours(1), Clock.fixed(NOW, ZoneOffset.UTC), false);
    }

    private void insert(int id, Instant claimedAt) {
        assertThat(repo.insertClaim(new SettlementRecord(hash(id), UUID.randomUUID(), "ab".repeat(32),
                "cardano:preprod", SettlementRecord.Status.SUBMITTED, null, null, "lovelace", null,
                "default", null, null, claimedAt, null, null, null, null, null, null,
                1, SettlementRecord.SubmissionProvenance.LOCAL, true, null))).isTrue();
    }

    private static String hash(int id) { return String.format("%064x", id); }
}
