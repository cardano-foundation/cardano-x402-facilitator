package org.cardanofoundation.x402.facilitator.chain;

import lombok.RequiredArgsConstructor;
import org.cardanofoundation.x402.facilitator.config.X402Properties;
import org.cardanofoundation.x402.facilitator.service.registry.CardanoNetworks;

import java.time.Instant;

/**
 * SlotConfig-based clock, using the standard Shelley-era slot-zero anchors:
 *   mainnet: zeroSlot 4492800, zeroTime 1596059091000 ms
 *   preprod: zeroSlot   86400, zeroTime 1655769600000 ms
 *   preview: zeroSlot       0, zeroTime 1666656000000 ms
 * slotLength 1000 ms for all three. Config-overridable per network entry.
 */
@RequiredArgsConstructor
public final class ShelleyNetworkClock implements NetworkClock {

    private final long zeroSlot;
    private final long zeroTimeMs;
    private final long slotLengthMs;

    public static ShelleyNetworkClock forNetwork(String network, X402Properties.SlotConfig override) {
        if (override != null && override.zeroSlot() != null && override.zeroTimeEpochSeconds() != null) {
            long len = override.slotLengthMs() == null ? 1000L : override.slotLengthMs();
            return new ShelleyNetworkClock(override.zeroSlot(), override.zeroTimeEpochSeconds() * 1000L, len);
        }
        return switch (CardanoNetworks.normalize(network)) {
            case CardanoNetworks.MAINNET -> new ShelleyNetworkClock(4492800L, 1596059091000L, 1000L);
            case CardanoNetworks.PREPROD -> new ShelleyNetworkClock(86400L, 1655769600000L, 1000L);
            case CardanoNetworks.PREVIEW -> new ShelleyNetworkClock(0L, 1666656000000L, 1000L);
            default -> throw new IllegalArgumentException("No slot config for network: " + network);
        };
    }

    @Override
    public long expectedSlotAt(Instant wallClock) {
        return zeroSlot + (wallClock.toEpochMilli() - zeroTimeMs) / slotLengthMs;
    }

    @Override
    public Instant slotToTime(long slot) {
        return Instant.ofEpochMilli(zeroTimeMs + (slot - zeroSlot) * slotLengthMs);
    }
}
