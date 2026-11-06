# WP-16: A 9th concurrent chain lookup waits within its budget instead of failing, still on at most 8 threads
Tier: R2
Base: 8b481e2
Check: new `LookupBudgetTest` methods red at base, green after; existing `LookupBudgetTest` unchanged and green; verify burst at 64 concurrent with 150 ms shim latency shows zero `chain_lookup_failed`

## Allowed files
- src/main/java/org/cardanofoundation/x402/facilitator/chain/LookupBudget.java
- src/main/java/org/cardanofoundation/x402/facilitator/chain/blockfrost/BlockfrostChainService.java
- src/test/java/org/cardanofoundation/x402/facilitator/chain/blockfrost/BlockfrostChainServiceTest.java
- src/test/java/org/cardanofoundation/x402/facilitator/chain/LookupBudgetTest.java
- docs/configuration.md
- docs/work/wp-16-lookup-pool-wait.md

## Rules
- **Invariants** (complexity-specialist reviews them before the change):
  1. At most 8 `chain-lookup` threads exist at any time, including calls that ignore interruption. The existing test `cancelledCallsThatIgnoreInterruptionCannotAccumulateBeyondEight` passes unchanged.
  2. A call waits for a thread at most until its request's remaining budget (`TIMEOUT`, 10 s) runs out. Then it is cancelled, it never runs later, and the caller gets `Exhausted` as today.
  3. The queue is bounded. When it is full, the call fails with `Exhausted` immediately, so overload still fails closed.
  4. A call that already started keeps today's semantics.
  5. Stale entries never hold queue capacity: a call that gives up removes its own queued task.
  6. Each request adds at most one queued identity-refresh call when the 30 s identity cache expires (BlockfrostChainService.java:82-83). Accepted and documented.
  7. **No thread waits on the lookup pool while holding a monitor.** This is a rule for every caller.
- **Shape of the change** (reviewed by complexity-specialist on 2026-11-04):
  - Executor: `ThreadPoolExecutor(8, 8, 30 s, new ArrayBlockingQueue<>(QUEUE_CAPACITY))` with `allowCoreThreadTimeOut(true)` (keep-alive > 0 is required).
  - `QUEUE_CAPACITY = 64`. Rationale: queued ≤ concurrent requests, since lookups within a request are sequential. 64 concurrent requests need ≤ 56 slots. A queue is only useful up to about 8·T/(k·L) − 8 (≈125 at 150 ms, ≈59 at 300 ms); beyond that it turns fast failures into 10 s failures.
  - On budget expiry, after `cancel(true)` in `finally`: `if (executor instanceof ThreadPoolExecutor t) t.remove((Runnable) future)`. `submit` returns the exact queued task. No `purge()` per call.
  - Queue time counts against the 10 s end-to-end budget; the 64-calls-per-request cap is unchanged.
- **New tests in `LookupBudgetTest`** (existing methods untouched). Each must be red at base **on its own**; today's pool already rejects a 9th call at once, so a test that only checks "not executed" or "Exhausted" would pass at base.
  - **In all three**: use `LookupBudget.newExecutor()` with the injected constructor, never the static pool. Wait on "entered" latches and poll `getQueue().size()`, never `sleep`. `shutdown` + `awaitTermination` in `finally`.
  - (a) With 8 calls blocked, a 9th waits. Before releasing, assert the queue size is 1 and its operation hasn't run. After releasing one, it completes within its budget.
  - (b) With 8 calls blocked, a 9th call with a short budget is **queued, not rejected**:
    - Start the stopwatch **before** constructing its `LookupBudget`, because the deadline starts at construction (LookupBudget.java:27).
    - It returns `Exhausted` only after at least its budget has elapsed.
    - Immediately after, `getQueue().isEmpty()`, which proves the `remove()`.
    - Then release the 8, `shutdown()` and `awaitTermination`. `shutdown` still runs queued tasks, which makes this the strongest check that the operation never ran: its counter is still 0.
  - (c) Read the capacity via `((ThreadPoolExecutor) executor).getQueue().remainingCapacity()`, so the file compiles at base, and require it to be ≥ 64. With 8 running, fill the queue to that capacity and wait for `queue.size() == capacity`. The next call is `Exhausted` in under 1 s, against a 30 s budget, and every queued call completes after release. At base the capacity is 0, so the ≥ 64 check fails.
- **`health()` must not wait under a monitor** (user decision 2026-11-04: in this package).
  - Today `health()` calls `requireNetworkIdentity` inside `synchronized (this)` (BlockfrostChainService.java:357-361). It runs for every fresh settle (FacilitatorController.java:70 → SettlementGate.java:55) and for readiness (ChainReadiness.java:35).
  - With waiting, a prober queued for up to 10 s would pin every virtual thread that blocks on that monitor (JDK 21, Dockerfile:17).
  - Change only that block: replace `synchronized (this)` with a `ReentrantLock` and `tryLock()`. A caller that doesn't get the lock returns the cached `lastProbeOk` result, exactly as the fresh-cache branch does.
  - Accepted trade-off: before the very first probe completes, concurrent callers see DOWN instead of waiting.
  - Submit (:223) stays as is: it holds no lock, waits only when the 30 s identity cache has expired, and still fails safely (`NotSubmitted`).
  - New unit test in `BlockfrostChainServiceTest`: while one `health()` call is blocked inside its probe, a second concurrent `health()` returns at once with the cached result (red at base: it blocks on the monitor). Existing tests stay unchanged.
- The existing `cancelledCalls…` test now queues its 9th call for its full 30 s budget (LookupBudgetTest.java:73-74). It still passes unchanged, only slower; log its new duration.
- Document the lookup concurrency limit and its behaviour in `docs/configuration.md` (one short section).

## Proof commands
- red-first for the health() test: `~/.claude/bin/red-first.sh 8b481e2 --baseline "./gradlew test --tests '*BlockfrostChainServiceTest'" src/test/java/org/cardanofoundation/x402/facilitator/chain/blockfrost/BlockfrostChainServiceTest.java -- ./gradlew test --tests '*BlockfrostChainServiceTest.<new method>'`
- red-first, once per new method `<m>` (a, b, c): `~/.claude/bin/red-first.sh 8b481e2 --baseline "./gradlew test --tests '*LookupBudgetTest'" src/test/java/org/cardanofoundation/x402/facilitator/chain/LookupBudgetTest.java -- ./gradlew test --tests '*LookupBudgetTest.<m>'`
- green: `./gradlew test` → all green
- burst proof (after WP-13's profile exists): with a **latency-only** shim profile (`latencyMs: 150`, no rate limit), run the verify burst at 1/8/16/32/64 concurrent before (old image) and after (rebuilt image), and log the outcome histograms. WP-18's profile can't be reused: its rate limit (10/s, burst 500) produces `chain_lookup_failed` regardless of the pool. Use the scratchpad `VerifyBurstProbe` or a latency-only run of WP-18's burst.
- scope: per-batch scope file

## Log
- 2026-11-04 Built by the orchestrator in a scratch copy of the working tree (rsync, without build/.gradle/node_modules), so image builds in the repo never saw half-edited code; the finished files were copied in afterwards.
- Changed:
  - `LookupBudget`: `ThreadPoolExecutor(THREADS=8, 8, 30 s, new ArrayBlockingQueue<>(QUEUE_CAPACITY=64))`, `allowCoreThreadTimeOut(true)`, `AbortPolicy`. In `call`'s `finally`, a call that gives up runs `cancel(true)` and then `if (executor instanceof ThreadPoolExecutor pool) pool.remove((Runnable) future)`. Class Javadoc: bounded wait, never under a monitor, never nested inside a lookup.
  - `BlockfrostChainService.health()`: `synchronized (this)` is replaced by `probeLock.tryLock()` (`ReentrantLock`). A caller that doesn't get the lock returns `cachedHealth()`, which is the former fresh-cache branch extracted unchanged.
  - Tests: `LookupBudgetTest` gains (a) `ninthCallWaitsForAFreeThreadInsteadOfFailing`, (b) `queuedCallThatOutlivesItsBudgetIsExhaustedRemovedAndNeverRuns` and (c) `fullQueueFailsFastAndEveryQueuedCallRunsAfterRelease`. `BlockfrostChainServiceTest` gains `concurrentHealthReturnsTheCachedResultWhileAProbeIsInFlight`. Existing methods are untouched.
  - `docs/configuration.md`: new section "Chain lookup concurrency".
- red-first (`~/.claude/bin/red-first.sh 8b481e2 --baseline "./gradlew test --tests '*LookupBudgetTest'" …LookupBudgetTest.java -- ./gradlew test --tests '*LookupBudgetTest.<m>'`, one run per method; the health test with the BlockfrostChainServiceTest baseline):
  - (a) "red-first: shown red at 8b481e2 … java.lang.AssertionError at LookupBudgetTest.java:237". The failure is `awaitQueueSize`: the 9th call is rejected at base, so the queue never reaches size 1.
  - (b) "shown red at 8b481e2 … AssertionError at LookupBudgetTest.java:154". The elapsed-time assertion fails because the call is rejected at once.
  - (c) after the review's upper bound: "shown red at 8b481e2 … AssertionError at LookupBudgetTest.java:176". The capacity at base is 0.
  - health: "shown red at 8b481e2 ('./gradlew test --tests *BlockfrostChainServiceTest.concurrentHealthReturnsTheCachedResultWhileAProbeIsInFlight'): > Task :test FAILED". The second caller blocks on the monitor, so `get(1 s)` times out.
- Green:
  - `LookupBudgetTest` 6/6 and `BlockfrostChainServiceTest` 23/23.
  - The existing `cancelledCallsThatIgnoreInterruptionCannotAccumulateBeyondEight` is unchanged and now takes **30.04 s**, because its 9th call queues for its whole 30 s budget.
  - The 4 new tests repeated 10× with `--rerun`: 10/10 exit 0.
  - Full `./gradlew test` after the review fixes: "BUILD SUCCESSFUL in 43s", 376 tests, 0 failures, 0 errors, 0 skipped.
- Review (code-reviewer): the code inside the Allowed files is sound; invariants 1-5 hold; `cachedHealth()` is token-identical to the old branch; the new tests are red at base and catch a missing `remove()`, an unbounded queue and losers that return DOWN. Fix round 1 (orchestrator):
  - The docs section is scoped to verification UTxO lookups and the identity check, "per lookup session", and "Concurrent health checks never wait for an in-flight probe".
  - Test (c) asserts `isBetween(64, 1024)`, so an unbounded-queue regression can't start billions of threads.
  - Javadoc: no nested pool calls.
  - M2 (a lost tryLock before the first probe reads "last Blockfrost probe failed") follows the brief. M4 (no test for `allowCoreThreadTimeOut`) is not required.
- Ruling (orchestrator), on invariant 7 for other callers: `SettlementReconciler.sweep()` is `synchronized` (SettlementReconciler.java:56) and reaches the pool through `checkInclusion` → `requireNetworkIdentity`. On JDK 21 with virtual threads that pins one carrier for up to the 10 s queue wait, at most once per 30 s identity window, and only while the pool is saturated. It already pinned during its direct HTTP calls before this change. The file is outside this package, so the exception is recorded here and the fix (`sweep()` → `ReentrantLock.tryLock`) goes to the user as a follow-up.
- Scoped re-review: I2, M1 and M3 ADDRESSED, "WP-16 re-review: CLEAN". Its one Minor wording point is applied: the verification session's own tip check runs on the pool, so the doc now says "Tip reads outside a verification session, and transaction, mempool and submit calls, run on the caller's own thread".
- **Burst proof, 2026-11-05, after WP-13.** Setup:
  - **Images:** "Before" is today's tree with only `LookupBudget.java`, `BlockfrostChainService.java` and their two tests reset to HEAD via `git show`, built as `x402-facilitator:pre-wp16` (`--platform linux/amd64`, like run.sh). "After" is today's `x402-facilitator:e2e`.
  - **Load:** each image runs a fresh `e2e/run.sh --up` with the shim profile `{"latencyMs":150}` (no rate limit). The scratchpad `VerifyBurstProbe` fires N simultaneous `/verify` calls with one valid payment, in 3 rounds per N, and polls readiness every 50 ms.
  - **Before (verbatim):**
    - `concurrency   8 x 3 rounds: {200 exact_cardano_facilitator_chain_lookup_failed=1, 200 valid=23} | latency ms p50=615 p95=685 max=685 | readiness DOWN 0/26 polls`
    - `concurrency  16 x 3 rounds: {200 exact_cardano_facilitator_chain_lookup_failed=24, 200 valid=24} | latency ms p50=644 p95=1676 max=1753`
    - `concurrency  32 x 3 rounds: {200 exact_cardano_facilitator_chain_lookup_failed=72, 200 valid=24} | latency ms p50=175 p95=1082 max=2106`
    - `concurrency  64 x 3 rounds: {200 exact_cardano_facilitator_chain_lookup_failed=169, 200 valid=23} | latency ms p50=1288 p95=1997 max=3314 | readiness DOWN 0/19 polls`
    - `concurrency 128 x 3 rounds: {200 exact_cardano_facilitator_chain_lookup_failed=361, 200 valid=23}`
  - **After (verbatim):**
    - `concurrency   8 x 3 rounds: {200 valid=24} | latency ms p50=676 p95=680 max=680 | readiness DOWN 0/28 polls`
    - `concurrency  16 x 3 rounds: {200 valid=48} | latency ms p50=1570 p95=1936 max=2596`
    - `concurrency  32 x 3 rounds: {200 valid=96} | latency ms p50=3050 p95=4634 max=4635`
    - `concurrency  64 x 3 rounds: {200 valid=192} | latency ms p50=4601 p95=6891 max=7415 | readiness DOWN 0/176 polls`
    - `concurrency 128 x 3 rounds: {200 exact_cardano_facilitator_chain_lookup_failed=176, 200 valid=208} | latency ms p50=4109 p95=7291 max=7788`
  - **Check met:** zero `chain_lookup_failed` at 64 concurrent with 150 ms latency (192/192 valid). Before WP-16 it was 169 of 192.
  - **Above the queue:** at 128, the 64-slot queue overflows and the excess fails closed at once, by design (invariant 3).
  - **Cost and readiness:** the cost is latency. At 64 concurrent, p95 is 6.9 s, still inside the 10 s budget. Readiness never went DOWN in either image. Both stacks were torn down, with 0 containers left.
