package org.cardanofoundation.x402.facilitator.chain;

import org.cardanofoundation.x402.facilitator.model.chain.UtxoState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

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
}
