package org.cardanofoundation.x402.facilitator.e2e.kit;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/** Condition polling with an explicit deadline — the suite never sleeps blind. */
public final class Poll {

    private static final Duration INTERVAL = Duration.ofMillis(500);

    private Poll() {
    }

    /**
     * @param what     what is awaited, for the failure message.
     * @param deadline how long to wait at most.
     * @param probe    empty while not ready; the value once ready.
     * @return the first non-empty probe result.
     * @throws AssertionError when the deadline passes first.
     */
    public static <T> T until(String what, Duration deadline, Supplier<Optional<T>> probe) {
        long end = System.nanoTime() + deadline.toNanos();
        RuntimeException last = null;
        while (true) {
            try {
                Optional<T> value = probe.get();
                if (value.isPresent()) return value.get();
            } catch (RuntimeException e) {
                last = e; // transient (e.g. the chain API mid-restart): keep polling
            }
            if (System.nanoTime() > end) {
                AssertionError error = new AssertionError("timed out after " + deadline.toSeconds() + " s waiting for " + what);
                if (last != null) error.addSuppressed(last);
                throw error;
            }
            try {
                Thread.sleep(INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + what, e);
            }
        }
    }
}
