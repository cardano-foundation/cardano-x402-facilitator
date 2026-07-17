package org.cardanofoundation.x402.facilitator.chain.yacistore;

import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.model.Era;
import org.cardanofoundation.x402.facilitator.chain.ChainLookupException;
import org.cardanofoundation.x402.facilitator.model.chain.InclusionResult;
import org.cardanofoundation.x402.facilitator.model.chain.SubmissionResult;
import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit coverage for the yaci-store tri-state UTxO resolution, inclusion, health, and era mapping. */
class YaciStoreChainServiceTest {

    // Mutable fakes for the four collaborators.
    final Map<String, YaciStoreChainService.UtxoSource.Utxo> outputs = new HashMap<>(); // "hash#i" -> utxo
    final Set<String> spent = new HashSet<>();
    long cursorSlot = 1_000_000L, cursorBlock = 500L, networkSlot = 1_000_000L;
    boolean cursorAvailable = true;
    boolean networkSlotKnown = true; // false => network tip never observed
    SubmissionResult submissionResult = new SubmissionResult.Accepted("deadbeef");

    YaciStoreChainService service(boolean keepUnknownWhenStale) {
        YaciStoreChainService.UtxoSource utxoSource = (h, i) -> Optional.ofNullable(outputs.get(h + "#" + i));
        YaciStoreChainService.SpentChecker spentChecker = (h, i) -> spent.contains(h + "#" + i);
        YaciStoreChainService.ChainTip tip = new YaciStoreChainService.ChainTip() {
            public OptionalLong currentSlot() { return cursorAvailable ? OptionalLong.of(cursorSlot) : OptionalLong.empty(); }
            public long currentBlock() { return cursorBlock; }
            public OptionalLong networkSlot() { return networkSlotKnown ? OptionalLong.of(networkSlot) : OptionalLong.empty(); }
            public boolean available() { return cursorAvailable; }
        };
        YaciStoreChainService.TxSubmitter submitter = tx -> submissionResult;
        return new YaciStoreChainService(utxoSource, spentChecker, tip, submitter,
                600L, keepUnknownWhenStale, Duration.ofMillis(5), Clock.systemUTC());
    }

    @Test void unspentOutputYieldsUnspentWithOwner() {
        outputs.put("aa#0", new YaciStoreChainService.UtxoSource.Utxo("addr_test1owner", 490L));
        assertThat(service(true).getUtxoState("AA", 0))
                .isEqualTo(new UtxoState.Unspent("addr_test1owner"));
    }

    @Test void consumedOutputYieldsSpentEvenWhenRowPresent() {
        outputs.put("aa#0", new YaciStoreChainService.UtxoSource.Utxo("addr_test1owner", 490L));
        spent.add("aa#0"); // pruning off: row remains, but tx_input records the spend
        assertThat(service(true).getUtxoState("aa", 0)).isInstanceOf(UtxoState.Spent.class);
    }

    @Test void absentOutputWhenCaughtUpIsSpent() {
        networkSlot = cursorSlot + 10; // within freshness window => caught up
        assertThat(service(true).getUtxoState("bb", 0)).isInstanceOf(UtxoState.Spent.class);
    }

    @Test void absentOutputWhenBehindIsUnknownUnderFailPolicy() {
        networkSlot = cursorSlot + 100_000; // far behind tip
        assertThat(service(true).getUtxoState("bb", 0)).isInstanceOf(UtxoState.Unknown.class);
    }

    @Test void absentOutputWhenBehindIsSpentUnderAssumeSpentPolicy() {
        networkSlot = cursorSlot + 100_000;
        assertThat(service(false).getUtxoState("bb", 0)).isInstanceOf(UtxoState.Spent.class);
    }

    @Test void absentOutputWhenNetworkTipNeverObservedFailsOpenToUnknown() {
        // A network tip that was never successfully fetched must NOT be read as
        // "caught up" (the old sentinel-0 bug): freshness fails open to Unknown so a
        // not-yet-indexed, legitimately-unspent input is not misclassified as Spent.
        networkSlotKnown = false;
        assertThat(service(true).getUtxoState("bb", 0)).isInstanceOf(UtxoState.Unknown.class);
    }

    @Test void getCurrentSlotThrowsWhenNoCursor() {
        cursorAvailable = false;
        assertThatThrownBy(() -> service(true).getCurrentSlot()).isInstanceOf(ChainLookupException.class);
    }

    @Test void submitDelegatesToSubmitter() {
        submissionResult = new SubmissionResult.Rejected("BadInputsUTxO");
        assertThat(service(true).submitTransaction(new byte[]{1})).isEqualTo(submissionResult);
    }

    @Test void checkInclusionComputesDepthFromOutputBlock() {
        cursorBlock = 500L;
        outputs.put("cc#0", new YaciStoreChainService.UtxoSource.Utxo("addr", 496L));
        InclusionResult r = service(true).checkInclusion("cc");
        assertThat(r).isInstanceOf(InclusionResult.Included.class);
        assertThat(((InclusionResult.Included) r).depth()).isEqualTo(5); // 500 - 496 + 1
    }

    @Test void checkInclusionNotSeenWhenNoOutput() {
        assertThat(service(true).checkInclusion("dd")).isInstanceOf(InclusionResult.NotSeen.class);
    }

    @Test void healthDownWhenNoCursor() {
        cursorAvailable = false;
        assertThat(service(true).health().healthy()).isFalse();
    }

    @Test void eraMapsToCorrectTxBodyType() {
        assertThat(YaciEra.txBodyType(Era.Conway)).isEqualTo(TxBodyType.CONWAY);
        assertThat(YaciEra.txBodyType(Era.Babbage)).isEqualTo(TxBodyType.BABBAGE);
        assertThat(YaciEra.txBodyType(Era.Alonzo)).isEqualTo(TxBodyType.ALONZO);
        assertThat(YaciEra.txBodyType(null)).isEqualTo(TxBodyType.CONWAY);
        assertThat(YaciEra.txBodyType(Era.Shelley)).isEqualTo(TxBodyType.CONWAY);
    }
}
