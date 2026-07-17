package org.cardanofoundation.x402.facilitator.chain;

import java.time.Instant;

/** Per-network slot arithmetic (Praos slot <-> wall clock). */
public interface NetworkClock {

    long expectedSlotAt(Instant wallClock);

    Instant slotToTime(long slot);
}
