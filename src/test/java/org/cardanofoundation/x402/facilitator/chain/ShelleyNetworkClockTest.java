package org.cardanofoundation.x402.facilitator.chain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShelleyNetworkClockTest {
    @Test void slotsBeforeTheAnchorRoundDownLikeTheTypeScriptClock() {
        var clock = new ShelleyNetworkClock(100, 1_000_000, 2_000);
        assertThat(clock.expectedSlotAt(Instant.ofEpochMilli(999_999))).isEqualTo(99);
        assertThat(clock.expectedSlotAt(Instant.ofEpochMilli(998_000))).isEqualTo(99);
        assertThat(clock.expectedSlotAt(Instant.ofEpochMilli(997_999))).isEqualTo(98);
        assertThat(clock.expectedSlotAt(Instant.ofEpochMilli(1_000_000))).isEqualTo(100);
        assertThat(clock.expectedSlotAt(Instant.ofEpochMilli(1_001_999))).isEqualTo(100);
        assertThat(clock.expectedSlotAt(Instant.ofEpochMilli(1_002_000))).isEqualTo(101);
    }

    @Test void invalidSlotLengthsFailAtConstruction() {
        for (long slotLength : new long[] {0, -1}) {
            assertThatThrownBy(() -> new ShelleyNetworkClock(100, 1_000_000, slotLength))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("slotLengthMs must be positive");
        }
    }
}
