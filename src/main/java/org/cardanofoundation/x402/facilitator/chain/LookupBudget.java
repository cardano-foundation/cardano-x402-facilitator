package org.cardanofoundation.x402.facilitator.chain;

import java.time.Duration;
import java.util.concurrent.*;

/**
 * A request has at most 64 external lookup calls and ten seconds for all of them together.
 * SDK calls run in a shared, bounded, non-queuing pool: even a provider that ignores
 * interruption cannot accumulate unlimited timed-out work. Saturation fails closed.
 */
public final class LookupBudget {
    public static final int MAX_CALLS = 64;
    public static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ExecutorService CALLS = newExecutor();

    // The same bounded policy is used by isolated executor tests.
    static ExecutorService newExecutor() {
        return new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "chain-lookup");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    private final ExecutorService executor;
    private final long started = System.nanoTime();
    private final long timeoutNanos;
    private final int maxCalls;
    private int calls;
    private boolean exhausted;

    public LookupBudget() { this(MAX_CALLS, TIMEOUT); }

    public LookupBudget(int maxCalls, Duration timeout) { this(maxCalls, timeout, CALLS); }

    LookupBudget(int maxCalls, Duration timeout, ExecutorService executor) {
        if (maxCalls <= 0 || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("positive lookup limits required");
        this.executor = executor;
        this.maxCalls = maxCalls;
        this.timeoutNanos = timeout.toNanos();
    }

    /** Checks time even when serving previously cached results. */
    public void checkDeadline() {
        if (exhausted || remainingNanos() <= 0 || Thread.currentThread().isInterrupted()) {
            exhausted = true;
            throw new Exhausted();
        }
    }

    private long remainingNanos() { return timeoutNanos - (System.nanoTime() - started); }

    public <T> T call(Callable<T> operation) {
        checkDeadline();
        if (calls >= maxCalls) { exhausted = true; throw new Exhausted(); }
        calls++;
        Future<T> future;
        try { future = executor.submit(operation); }
        catch (RejectedExecutionException e) { exhausted = true; throw new Exhausted(); }
        try {
            T result = future.get(Math.max(0, remainingNanos()), TimeUnit.NANOSECONDS);
            checkDeadline();
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exhausted = true;
            throw new Exhausted();
        } catch (TimeoutException e) {
            exhausted = true;
            throw new Exhausted();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ChainLookupException lookup) throw lookup;
            throw new ChainLookupException("provider lookup failed", e.getCause());
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    public static final class Exhausted extends ChainLookupException {
        private Exhausted() { super("request lookup budget or deadline exhausted"); }
    }
}
