package org.cardanofoundation.x402.facilitator.chain;

import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LookupBudgetTest {
    @Test void customProviderDefaultSessionIsBoundedAndCachesResults() {
        FacilitatorChainService provider = mock(FacilitatorChainService.class);
        when(provider.openUtxoLookup()).thenCallRealMethod();
        when(provider.getUtxoState(anyString(), anyInt())).thenReturn(new UtxoState.Spent(null));
        var session = provider.openUtxoLookup();
        for (int i = 0; i < 64; i++) {
            assertThat(session.getUtxoState("AA", i)).isInstanceOf(UtxoState.Spent.class);
            assertThat(session.getUtxoState("aa", i)).isInstanceOf(UtxoState.Spent.class);
        }
        assertThat(session.getUtxoState("aa", 64)).isInstanceOf(UtxoState.Unknown.class);
        verify(provider, times(64)).getUtxoState(anyString(), anyInt());
    }

    @Test void callsAfterExhaustionNeverRun() {
        var budget = new LookupBudget(2, Duration.ofSeconds(2));
        var calls = new AtomicInteger();
        assertThat(budget.call(calls::incrementAndGet)).isEqualTo(1);
        assertThat(budget.call(calls::incrementAndGet)).isEqualTo(2);
        assertThatThrownBy(() -> budget.call(calls::incrementAndGet)).isInstanceOf(LookupBudget.Exhausted.class);
        assertThatThrownBy(() -> budget.call(calls::incrementAndGet)).isInstanceOf(LookupBudget.Exhausted.class);
        assertThat(calls).hasValue(2);
    }

    @Test void cancelledCallsThatIgnoreInterruptionCannotAccumulateBeyondEight() throws Exception {
        var executor = LookupBudget.newExecutor();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        var callers = new java.util.ArrayList<Thread>();
        try {
            for (int i = 0; i < 8; i++) {
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch callerFinished = new CountDownLatch(1);
                var outcome = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread caller = new Thread(() -> {
                    try {
                        new LookupBudget(1, Duration.ofSeconds(30), executor).call(() -> {
                            active.incrementAndGet();
                            entered.countDown();
                            while (release.getCount() > 0) {
                                try { release.await(); } catch (InterruptedException ignored) { }
                            }
                            return null;
                        });
                    } catch (Throwable failure) {
                        outcome.set(failure);
                    } finally {
                        callerFinished.countDown();
                    }
                });
                callers.add(caller);
                caller.start();
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .as("provider call %s started before caller cancellation", i).isTrue();
                caller.interrupt();
                assertThat(callerFinished.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(outcome.get()).isInstanceOf(LookupBudget.Exhausted.class);
            }
            assertThat(active).hasValue(8);
            var calls = new AtomicInteger();
            assertThatThrownBy(() -> new LookupBudget(1, Duration.ofSeconds(30), executor)
                    .call(calls::incrementAndGet)).isInstanceOf(LookupBudget.Exhausted.class);
            assertThat(calls).hasValue(0);
        } finally {
            release.countDown();
            callers.forEach(Thread::interrupt);
            for (Thread caller : callers) caller.join(5_000);
            executor.shutdownNow();
            // Cleanup must not replace the actual test assertion if startup failed.
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(executor.isTerminated()).isTrue();
    }

    @Test void ninthCallWaitsForAFreeThreadInsteadOfFailing() throws Exception {
        ExecutorService executor = LookupBudget.newExecutor();
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        List<CountDownLatch> releases = new ArrayList<>();
        for (int i = 0; i < 8; i++) releases.add(new CountDownLatch(1));
        List<Thread> threads = new ArrayList<>();
        try {
            threads.addAll(occupyAllEightThreads(executor, releases));
            AtomicInteger ran = new AtomicInteger();
            AtomicReference<Object> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch ninthDone = new CountDownLatch(1);
            Thread ninth = new Thread(() -> {
                try {
                    result.set(new LookupBudget(1, Duration.ofSeconds(10), executor).call(() -> {
                        ran.incrementAndGet();
                        return "ninth";
                    }));
                } catch (Throwable e) {
                    failure.set(e);
                } finally {
                    ninthDone.countDown();
                }
            });
            threads.add(ninth);
            ninth.start();

            awaitQueueSize(pool, 1);
            assertThat(pool.getQueue()).hasSize(1);
            assertThat(ran).as("the queued call must not run while all eight threads are busy").hasValue(0);

            releases.get(0).countDown();
            assertThat(ninthDone.await(10, TimeUnit.SECONDS)).as("ninth call completed within its budget").isTrue();
            assertThat(failure.get()).isNull();
            assertThat(result.get()).isEqualTo("ninth");
            assertThat(ran).hasValue(1);
        } finally {
            releases.forEach(CountDownLatch::countDown);
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            for (Thread thread : threads) thread.join(5_000);
        }
    }

    @Test void queuedCallThatOutlivesItsBudgetIsExhaustedRemovedAndNeverRuns() throws Exception {
        ExecutorService executor = LookupBudget.newExecutor();
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> holders = new ArrayList<>();
        AtomicInteger ran = new AtomicInteger();
        Duration budget = Duration.ofMillis(300);
        try {
            holders.addAll(occupyAllEightThreads(executor, Collections.nCopies(8, release)));
            // The deadline starts when the budget is constructed, so the stopwatch starts first.
            long start = System.nanoTime();
            LookupBudget lookup = new LookupBudget(1, budget, executor);
            assertThatThrownBy(() -> lookup.call(ran::incrementAndGet)).isInstanceOf(LookupBudget.Exhausted.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            assertThat(elapsed).as("queued, not rejected: Exhausted only once its budget ran out").isGreaterThanOrEqualTo(budget);
            assertThat(pool.getQueue()).as("a call that gives up removes its own queued task").isEmpty();
        } finally {
            release.countDown();
            // shutdown() still runs queued tasks: the strongest check that the expired call never ran.
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            for (Thread holder : holders) holder.join(5_000);
        }
        assertThat(executor.isTerminated()).isTrue();
        assertThat(ran).hasValue(0);
    }

    @Test void fullQueueFailsFastAndEveryQueuedCallRunsAfterRelease() throws Exception {
        ExecutorService executor = LookupBudget.newExecutor();
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        try {
            threads.addAll(occupyAllEightThreads(executor, Collections.nCopies(8, release)));
            int capacity = pool.getQueue().remainingCapacity();
            // The upper bound keeps an unbounded-queue regression from starting billions of threads below.
            assertThat(capacity).as("bounded queue capacity").isBetween(64, 1024);

            AtomicInteger queuedRan = new AtomicInteger();
            Queue<Throwable> queuedFailures = new ConcurrentLinkedQueue<>();
            CountDownLatch queuedDone = new CountDownLatch(capacity);
            for (int i = 0; i < capacity; i++) {
                Thread caller = new Thread(() -> {
                    try {
                        new LookupBudget(1, Duration.ofSeconds(30), executor).call(queuedRan::incrementAndGet);
                    } catch (Throwable e) {
                        queuedFailures.add(e);
                    } finally {
                        queuedDone.countDown();
                    }
                });
                threads.add(caller);
                caller.start();
            }
            awaitQueueSize(pool, capacity);

            AtomicInteger overflowRan = new AtomicInteger();
            long start = System.nanoTime();
            assertThatThrownBy(() -> new LookupBudget(1, Duration.ofSeconds(30), executor).call(overflowRan::incrementAndGet))
                    .isInstanceOf(LookupBudget.Exhausted.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("a full queue fails closed at once")
                    .isLessThan(Duration.ofSeconds(1));

            release.countDown();
            assertThat(queuedDone.await(10, TimeUnit.SECONDS)).as("every queued call completed").isTrue();
            assertThat(queuedFailures).isEmpty();
            assertThat(queuedRan).hasValue(capacity);
            assertThat(overflowRan).hasValue(0);
        } finally {
            release.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            for (Thread thread : threads) thread.join(5_000);
        }
    }

    /** Starts one caller per release latch; each blocks a pool thread until its latch opens. */
    private static List<Thread> occupyAllEightThreads(ExecutorService executor, List<CountDownLatch> releases)
            throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(releases.size());
        List<Thread> holders = new ArrayList<>();
        for (CountDownLatch release : releases) {
            Thread holder = new Thread(() -> new LookupBudget(1, Duration.ofSeconds(30), executor).call(() -> {
                entered.countDown();
                release.await();
                return null;
            }));
            holders.add(holder);
            holder.start();
        }
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("all eight lookup threads busy").isTrue();
        return holders;
    }

    private static void awaitQueueSize(ThreadPoolExecutor pool, int size) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.getQueue().size() != size) {
            if (System.nanoTime() > deadline)
                fail("lookup queue never reached size %s (now %s)", size, pool.getQueue().size());
            Thread.onSpinWait();
        }
    }
}
