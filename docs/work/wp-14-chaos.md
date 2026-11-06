# WP-14: A facilitator restart or a Postgres outage never double-broadcasts or reports success without inclusion
Tier: R1
Base: 8b481e2
Check: `CrashRecoveryE2ETest` green in `e2e/run.sh`, and the stack is healthy afterwards

## Allowed files
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/CrashRecoveryE2ETest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/Stack.java
- docs/work/wp-14-chaos.md

## Rules
- `@Isolated`: it runs inside `e2eTest` with no other class alongside it (no tag, no separate pass).
- `kit/Stack.java` wraps `docker compose -p $E2E_COMPOSE_PROJECT -f $E2E_COMPOSE_FILE <kill|up -d|stop|start|exec>`, reusing however `ChainOutageE2ETest` already shells out. Every test restores the stack in `finally`: containers running, **both** facilitators' readiness UP (a Postgres stop hits both), shim faults cleared. WP-11's `IsolatedStackGuard` double-checks after the class.
- **Restart mid-settle**:
  1. Register `hide-tx` for the payment's hash and start `/settle` on a background thread.
  2. Once the shim's submit count for it is 1: `kill facilitator`, then `start facilitator` (or `up -d --no-deps --no-recreate facilitator`), and wait for readiness UP. A plain `up -d` can recreate shim or postgres if the test JVM's environment differs from run.sh's, which would reset the shim's counters and faults.
  3. Clear the fault.
  4. Assert:
     - re-settle of the same payload → `confirmed`;
     - submit count still 1;
     - the tx is on chain once (Yano direct);
     - without any re-settle, a second payment interrupted the same way reaches `CONFIRMED` in `facilitator.settlement` within 90 s, counted from readiness UP (the reconciler's 30 s initial delay, SettlementReconciler.java:55, plus a slow emulated amd64 boot). This proves the reconciler. Read via `Stack.exec postgres psql …`; confirm the table and column names first.
- **Postgres outage**:
  1. `stop postgres`; within 30 s, facilitator readiness DOWN (the `db` probe is in the readiness group, application.yml). The payment's TTL must outlast the outage plus the Hikari connection timeout that a settle blocks on. Verify rejects a TTL more than `maxTimeoutSeconds` (600) past the tip (ExactCardanoScheme.java:151-155, PaymentFactory.java:33-34, 172), so a larger `ttlSlots` fails with `ttl_too_far` instead. Keep the default 300, or use exactly 600, and keep the outage under about 120 s.
  2. A fresh payment's `/settle` never returns `success: true`, and its submit count is 0 (fail closed: no claim, no broadcast).
  3. Record `/verify`'s response in the Log (not asserted).
  4. `start postgres` → readiness UP within 60 s → the same payment settles `confirmed` with submit count 1.
- If any expectation above disagrees with the facilitator, **stop and report**: that's a durability bug or a requirement change.

## Proof commands
- red-first: n/a (characterization)
- `e2e/run.sh --keep --tests '*CrashRecoveryE2ETest'` → green; then `e2e/run.sh --reuse --tests '*RoutesE2ETest'` → green (the stack is left healthy)
- full `e2e/run.sh` → green (isolation holds with parallel classes)
- scope: per-batch scope file

## Log

### WP-14 implementation log
Files: `kit/Stack.java` (new: kill/start/stop/upNoRecreate/psql/compose via `docker compose -p $E2E_COMPOSE_PROJECT -f $E2E_COMPOSE_FILE`), `CrashRecoveryE2ETest.java` (new, `@Isolated @StackRequired`, 2 tests), this Log.
Tests: `aRestartMidSettleNeverRebroadcastsAndTheReconcilerConfirms`, `aPostgresOutageFailsClosedAndRecovers`.
Journal: table `facilitator.settlement`, columns `tx_hash`, `status` (migrations V1/V2; enum CLAIMED, SUBMITTING, SUBMITTED, NOT_CONFIRMED, CONFIRMED, FAILED, EXPIRED). Default facilitator DB is `postgres`.
Restart used `kill` + `start` (no recreate). Each test restores in `finally`: `up -d --no-deps --no-recreate` for postgres, facilitator, facilitator-mempool; `shim.reset()`; both readiness UP.

Measurements (Gradle XML stdout, 2nd run):
- readiness DOWN after `stop postgres`: 30316 ms measured from before the stop call (the stop itself takes about 10 s; the 30 s poll deadline starts after it returns)
- readiness UP after `start postgres`: 9020 ms
- reconciler CONFIRMED (no re-settle), from readiness UP: 28138 ms (limit 90 s)
- `/verify` during the Postgres outage: `HTTP 200 {"isValid":true,"payer":"addr_test1qzhn7rgss4gncfl7zgjp9cdqymqnspqkqpka8wv5mmk2kn05deys32436mpg9cctudsd37mt8k5lpajkgukwq39sw0ascmvczn"}` (not asserted)
- `/settle` during the outage: `HTTP 500 {"error":"internal_error","correlationId":"a34112ce-..."}`; submit count 0; after the restart the same payment settled confirmed, submit count 1.
- Facilitator restart time (kill+start to readiness UP) is not separately logged; the tests passed within the 240 s bound.

Proofs (verbatim tails):
1. `e2e/run.sh --keep --tests '*CrashRecoveryE2ETest'`: `CrashRecoveryE2ETest > aPostgresOutageFailsClosedAndRecovers() PASSED`, `CrashRecoveryE2ETest > aRestartMidSettleNeverRebroadcastsAndTheReconcilerConfirms() PASSED`, `BUILD SUCCESSFUL in 3m 39s`, `=== e2e: PASSED` (re-run after adding stdout notes: same result)
2. `e2e/run.sh --reuse --tests '*RoutesE2ETest'`: `BUILD SUCCESSFUL in 14s`, `=== e2e: PASSED`
3. full fresh `e2e/run.sh` (E2E_SKIP_BUILD=1, after `down -v`): `BUILD SUCCESSFUL in 7m 47s`, TS suite `tests 6`, `=== e2e: PASSED`; 176 " PASSED" lines in the log including both Crash tests, no FAILED/SKIPPED. run.sh tore the stack down (compose ps empty).
4. check-scope.sh against this brief lists 59 files: all earlier staged WP changes (base 8b481e2). The only untracked files from this WP are the two new ones, plus this brief.

Assumed: 176 PASSED lines = 173 + 2 new + 1 unexplained (not investigated). `Funding.wallet(10)` used for payers.
Unsure: DOWN-within-30 s is tight (30 s poll after a ~10 s stop); it passed, but a slow host could flake. Reconciler margin 28 s vs 90 s.
Skipped: no devkit restart needed; no production code touched.

### Fix round 1
- I1: `second` is now built just before `interruptMidSettle(second, ...)`, after the first reboot and re-settle, so a slow host cannot expire its TTL.
- I2: DOWN clock starts after `Stack.stop(postgres)` returns; 45 s deadline (Hikari 30 s connectionTimeout + 15 s slack); explicit `assertThat(down).isLessThanOrEqualTo(45 s)`. Ruling: the brief 30 s assumed an instant probe failure; the db probe can block for Hikari 30 s. Measured: READINESS_DOWN_AFTER_STOP_RETURNED_MS=30124 (confirms the probe blocks about 30 s).
- M1: `Stack.compose` now drains stdout (stderr merged) with readAllBytes before waiting; a daemon watchdog destroys a child after 90 s so the read cannot hang. ChainOutageE2ETest untouched.
- M2: new `killMidSettle`: before each kill, psql asserts the journal row is SUBMITTING or SUBMITTED; after the kill the background settle result is awaited (60 s) and, if it answered, its body must not contain `"success":true`.
- Proof: `e2e/run.sh --keep --tests *CrashRecoveryE2ETest`: both tests PASSED, BUILD SUCCESSFUL in 3m 36s, `=== e2e: PASSED`. Measured: reconciler CONFIRMED 28095 ms after ready; postgres UP 9160 ms; settle during outage HTTP 500.
- Proof: `e2e/run.sh --reuse --tests *RoutesE2ETest`: BUILD SUCCESSFUL in 15s, `=== e2e: PASSED`. Stack torn down (compose ps empty). Full run skipped as instructed.

### Rulings, re-review and closing changes (orchestrator)
- **DOWN bound (ruling, 2026-11-05):** the brief's "readiness DOWN within 30 s" assumed the db probe fails at once. It actually blocks for up to Hikari's default 30 s `connectionTimeout`, because nothing overrides it. The enforced bound is therefore 45 s, measured from when `stop` returns. The measured value is 30124 ms.
- **Scoped re-review of fix round 1:** I1, I2, M1 and M2 are ADDRESSED. Every original check is kept, and the only change is the ruled bound. Result: "WP-14 re-review: CLEAN".
- **Closing changes,** applied from that re-review:
  - The `Stack` watchdog also destroys the CLI's descendants, so a compose plugin can't hold stdout open.
  - The interrupted settle's answer is parsed as JSON on a 200, not text-matched.
- **Closing run:** `E2E_SKIP_BUILD=1 e2e/run.sh --tests '*CrashRecoveryE2ETest'` on a fresh stack. Both tests PASSED, "BUILD SUCCESSFUL in 3m 21s", "=== e2e: PASSED", 0 containers left.
- **Scope:** checked against the run's batch scope file. Verbatim: "in scope: 62 changed file(s) match 61 glob(s)" (exit 0).
- **The "176th PASSED"** in the full run is run.sh's own `=== e2e: PASSED` line, so the suite ran 175 tests.
