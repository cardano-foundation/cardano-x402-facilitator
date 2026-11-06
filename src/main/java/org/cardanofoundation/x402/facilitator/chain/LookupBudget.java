package org.cardanofoundation.x402.facilitator.chain;

import java.time.Duration;
import java.util.concurrent.*;

/**
 * A request has at most 64 external lookup calls and ten seconds for all of them together.
 * SDK calls run in a shared pool of at most {@value #THREADS} threads, so even a provider
 * that ignores interruption cannot accumulate unlimited timed-out work. A call that finds
 * every thread busy waits in a bounded queue, but only for its request's remaining budget;
 * then it is cancelled and removed, and never runs. A full queue fails closed at once.
 * Never wait on this pool while holding a monitor, and never call it from inside a lookup
 * that already runs on it: eight such parents would wait on their own queued children.
 */
public final class LookupBudget {
    public static final int MAX_CALLS = 64;
    public static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final int THREADS = 8;
    /**
     * Lookups within one request run one after another, so a request holds at most one
     * queued call: 64 slots cover 72 concurrent requests. A deeper queue would only turn
     * fast failures into ten-second ones.
     */
    static final int QUEUE_CAPACITY = 64;
    private static final ExecutorService CALLS = newExecutor();

    // The same bounded policy is used by isolated executor tests.
    static ExecutorService newExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                Thread thread = new Thread(runnable, "chain-lookup");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        // Idle threads still retire after 30 s, as with the former core size of 0.
        executor.allowCoreThreadTimeOut(true);
        return executor;
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
            if (!future.isDone()) {
                future.cancel(true);
                // A cancelled task never runs, but it would still hold its queue slot.
                if (executor instanceof ThreadPoolExecutor pool) pool.remove((Runnable) future);
            }
        }
    }

    public static final class Exhausted extends ChainLookupException {
        private Exhausted() { super("request lookup budget or deadline exhausted"); }
    }
}
