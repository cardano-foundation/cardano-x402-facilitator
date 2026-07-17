package org.cardanofoundation.x402.facilitator.config;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionRequest;
import com.bloxbean.cardano.yaci.helper.LocalTxSubmissionClient;
import com.bloxbean.cardano.yaci.helper.model.TxResult;
import com.bloxbean.cardano.yaci.store.common.domain.Cursor;
import com.bloxbean.cardano.yaci.store.common.domain.SyncStatus;
import com.bloxbean.cardano.yaci.store.common.service.CursorService;
import com.bloxbean.cardano.yaci.store.core.service.SyncStatusService;
import com.bloxbean.cardano.yaci.store.core.storage.api.EraStorage;
import com.bloxbean.cardano.yaci.store.epoch.storage.EpochParamStorage;
import com.bloxbean.cardano.yaci.store.utxo.storage.UtxoStorage;
import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.chain.yacistore.YaciEra;
import org.cardanofoundation.x402.facilitator.chain.yacistore.YaciStoreChainService;
import org.cardanofoundation.x402.facilitator.chain.yacistore.YaciStoreProtocolParamsProvider;
import org.cardanofoundation.x402.facilitator.model.chain.ProtocolParams;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * yaci-store backend wiring — active ONLY under the {@code yaci-store} Spring
 * profile (spec sections 9.3 / 12). Imports the yaci-store library configurations
 * so their stores/services exist, then adapts them onto the pure
 * {@link YaciStoreChainService} collaborators. The default (blockfrost) context
 * never loads this class, so it cannot affect that path.
 *
 * <p><b>Runtime prerequisites</b> (not exercisable without a synced node): a
 * reachable cardano-node (N2N sync source + N2C submission socket) and a Postgres
 * with the yaci-store schema (Flyway {@code classpath:db/store/postgresql}). N2C
 * submission needs a {@link LocalTxSubmissionClient} bean bound to the node
 * socket; when absent, submission returns {@code NotSubmitted} (the read/confirm
 * path still works). See deploy/README.md.
 */
@Configuration
@Profile("yaci-store")
@Import({
        com.bloxbean.cardano.yaci.store.core.StoreConfiguration.class,
        com.bloxbean.cardano.yaci.store.utxo.UtxoStoreConfiguration.class,
        com.bloxbean.cardano.yaci.store.epoch.EpochStoreConfiguration.class
})
public class YaciStoreConfiguration {

    private static final Logger log = LogManager.getLogger(YaciStoreConfiguration.class);
    private static final Duration NETWORK_TIP_TTL = Duration.ofSeconds(15);
    private static final Duration SUBMIT_TIMEOUT = Duration.ofSeconds(20);

    /** Cached network tip: when it was fetched, its slot, and whether it was ever observed. */
    private record NetworkTip(long fetchedAtMillis, long slot, boolean known) {
    }

    @Bean
    public YaciStoreChainService.SpentChecker yaciSpentChecker(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return (txHash, index) -> {
            try {
                Integer hit = jdbc.query(
                        "SELECT 1 FROM tx_input WHERE tx_hash = ? AND output_index = ? LIMIT 1",
                        rs -> rs.next() ? 1 : 0, txHash, index);
                return hit != null && hit == 1;
            } catch (RuntimeException e) {
                throw new ChainLookupException("yaci-store tx_input lookup failed: " + e.getMessage());
            }
        };
    }

    @Bean
    public YaciStoreChainService.UtxoSource yaciUtxoSource(UtxoStorage utxoStorage) {
        return (txHash, index) -> utxoStorage.findById(txHash, index)
                .map(u -> new YaciStoreChainService.UtxoSource.Utxo(
                        u.getOwnerAddr(), u.getBlockNumber() == null ? 0L : u.getBlockNumber()));
    }

    @Bean
    public YaciStoreChainService.ChainTip yaciChainTip(CursorService cursorService,
                                                       SyncStatusService syncStatusService,
                                                       Clock facilitatorClock) {
        // Local cursor (cheap) for slot/block; network tip (SyncStatus) cached to
        // keep freshness checks off the hot path.
        AtomicReference<NetworkTip> cachedNetworkTip =
                new AtomicReference<>(new NetworkTip(Long.MIN_VALUE, 0L, false));
        return new YaciStoreChainService.ChainTip() {
            @Override
            public OptionalLong currentSlot() {
                return cursorService.getCursor().map(Cursor::getSlot)
                        .map(OptionalLong::of).orElse(OptionalLong.empty());
            }

            @Override
            public long currentBlock() {
                return cursorService.getCursor().map(Cursor::getBlock).orElse(0L);
            }

            @Override
            public OptionalLong networkSlot() {
                long now = facilitatorClock.millis();
                NetworkTip cached = cachedNetworkTip.get();
                if (now - cached.fetchedAtMillis() > NETWORK_TIP_TTL.toMillis()) {
                    try {
                        SyncStatus status = syncStatusService.getSyncStatus();
                        cached = new NetworkTip(now, status.networkSlot(), true);
                        cachedNetworkTip.set(cached);
                    } catch (RuntimeException e) {
                        // Keep the last known network tip on a transient failure; if it
                        // was never observed, report empty so freshness fails open.
                        log.warn("yaci-store network tip fetch failed; network tip is {}",
                                cached.known() ? "stale but usable" : "unknown", e);
                    }
                }
                return cached.known() ? OptionalLong.of(cached.slot()) : OptionalLong.empty();
            }

            @Override
            public boolean available() {
                return cursorService.getCursor().isPresent();
            }
        };
    }

    @Bean
    public YaciStoreChainService.TxSubmitter yaciTxSubmitter(EraStorage eraStorage,
                                                             ObjectProvider<LocalTxSubmissionClient> clientProvider) {
        return txBytes -> {
            LocalTxSubmissionClient client = clientProvider.getIfAvailable();
            if (client == null) {
                return new SubmissionResult.NotSubmitted(
                        "no LocalTxSubmissionClient bean — bind the cardano-node N2C socket to enable submission");
            }
            // Era lookup can hit the DB and must not escape the lambda: every other
            // failure here resolves to a SubmissionResult, so a transient era-store
            // error would otherwise break the /settle JSON contract. If the era is
            // unavailable, fall back to the current era's body type (CONWAY) and still
            // attempt submission rather than leaving the claim stranded pre-broadcast.
            Era era;
            try {
                era = eraStorage.findCurrentEra().map(e -> e.getEra()).orElse(null);
            } catch (RuntimeException e) {
                era = null; // YaciEra.txBodyType(null) => CONWAY
                log.warn("yaci-store era lookup failed; defaulting to CONWAY tx body type", e);
            }
            try {
                TxResult result = client.submitTx(
                        new TxSubmissionRequest(YaciEra.txBodyType(era), txBytes)).block(SUBMIT_TIMEOUT);
                if (result == null) return new SubmissionResult.Unknown("no submission result (timed out)");
                return result.isAccepted()
                        ? new SubmissionResult.Accepted(result.getTxHash())
                        : new SubmissionResult.Rejected(result.getErrorCbor());
            } catch (RuntimeException e) {
                // Threw after the wire — the node may have accepted; never release the claim.
                return new SubmissionResult.Unknown(e.getMessage());
            }
        };
    }

    @Bean
    public YaciStoreProtocolParamsProvider.LatestParams yaciLatestParams(EpochParamStorage epochParamStorage) {
        return () -> epochParamStorage.getLatestEpochParam().map(ep -> new ProtocolParams(
                ep.getParams().getAdaPerUtxoByte(), ep.getParams().getMaxTxSize()));
    }

    @Bean
    public YaciStoreBackendProvider yaciStoreBackendProvider(
            YaciStoreChainService.UtxoSource utxoSource,
            YaciStoreChainService.SpentChecker spentChecker,
            YaciStoreChainService.ChainTip chainTip,
            YaciStoreChainService.TxSubmitter txSubmitter,
            YaciStoreProtocolParamsProvider.LatestParams latestParams,
            X402Properties props,
            Clock facilitatorClock) {
        long tipFreshnessSlots = props.chain() == null || props.chain().tipFreshnessSlots() == null
                ? 600L : props.chain().tipFreshnessSlots();
        boolean keepUnknownWhenStale = props.chain() == null || props.chain().utxoUnknownPolicy() == null
                || !"assume-spent".equals(props.chain().utxoUnknownPolicy());
        Duration poll = props.settle() == null ? Duration.ofSeconds(3) : props.settle().pollIntervalOrDefault();
        YaciStoreChainService chainService = new YaciStoreChainService(
                utxoSource, spentChecker, chainTip, txSubmitter,
                tipFreshnessSlots, keepUnknownWhenStale, poll, facilitatorClock);
        YaciStoreProtocolParamsProvider paramsProvider = new YaciStoreProtocolParamsProvider(latestParams);
        // yaci-store indexes a single chain; StartupValidation enforces <= 1 yaci
        // network, so the same services back whichever network entry uses this mode.
        return (entry, p, clock) -> new ChainBackendFactory.ChainBackend(chainService, paramsProvider, clock);
    }
}
