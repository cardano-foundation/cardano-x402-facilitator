# WP-15: With mempool acceptance off, `l1Confirmations: -1` waits for inclusion instead of answering pending at mempool sight
Tier: R2
Base: 8b481e2
Check: `SettlementMempoolDepthTest` and `MempoolEvidenceE2ETest` red before the fix, green after; `./gradlew test` and `e2e/run.sh` green

## Allowed files
- src/main/java/org/cardanofoundation/x402/facilitator/service/settlement/SettlementService.java
- src/test/java/org/cardanofoundation/x402/facilitator/service/settlement/SettlementMempoolDepthTest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/MempoolEvidenceE2ETest.java
- docs/work/wp-15-mempool-depth-fix.md

## Rules
- **Invariant** (reviewed by complexity-specialist on 2026-11-04):
  - **Scope:** the fresh-submit path, after an `Accepted` submission whose hash matches. `Unknown`, a mismatched hash and a lost CAS still return pending without waiting (SettlementService.java:78, 100, 105).
  - With `accept-mempool=false`, that settle does not stop waiting at mempool evidence. It returns `confirmed` once inclusion reaches depth ≥ `max(0, max(requested, stored selected_confirmations))` (SettlementService.java:173-175). Otherwise it returns `settlement_pending` when the timeout ends, which can overshoot by one lookup budget, as today.
  - With `accept-mempool=true`, behaviour is unchanged.
  - Retries (`resume()`, SettlementService.java:137-164) answer from a single `checkInclusion` by design and are out of scope. The reconciler already uses `max(0, selected)` (SettlementReconciler.java:103).
  - No funds regression: during the wait the row is SUBMITTED, so retries go to `resume()` and never broadcast; success still requires `Included` (:175). The only cost is provider polling equal to a depth-0 settle.
- **Fix site only**: `SettlementService.java:119` awaits at `config.acceptMempool() ? depth : Math.max(0, depth)`. With depth 0, `awaitInclusion` polls past `Mempool` (BlockfrostChainService.java:326-328) and `observe()` confirms at SettlementService.java:175. `observe()`, `resume()` and the journal stay untouched; the requested depth stored in the journal stays as requested.
- **Unit test** (`SettlementMempoolDepthTest`):
  - Template: `ClientSubmissionTest`.
  - Subclass its `FakeChainService`; override `awaitInclusion` to **record every `minDepth` it receives** and return `Mempool` when `minDepth <= -1`, `Included(depth 0)` otherwise. Set `chain.includedDepth = MEMPOOL`, so the rest of the fake agrees (its default `Included(1)` at FakeChainService.java:48 would mask a fix that falls back to `checkInclusion`).
  - Main case, accept-mempool=false and `l1Confirmations: -1` (red at base). Assert:
    - success with `confirmations == 0`;
    - recorded minDepth is `[0]`;
    - `submitCount == 1`;
    - journal status CONFIRMED;
    - stored `selected_confirmations` is still -1.
  - Guard cases (green at base too, they pin the fix's edges):
    - Mempool for every minDepth (never included) → pending at -1, row NOT_CONFIRMED.
    - `requirements(1)` → recorded minDepth `[1]`, which catches a hard-coded 0.
    - accept-mempool=true with -1 → "mempool" success, and `awaitInclusion` is never called (SettlementService.java:112).
  - `ClientSubmissionTest#minusOneWithoutOperatorOptInStillRequiresCanonicalInclusion` must keep passing unchanged.
- **E2E test** (`MempoolEvidenceE2ETest`, `@Isolated`):
  - On the default instance (accept-mempool off), register shim `mempool` + `hide-tx` (which hides `/txs/<hash>` only, see WP-13) for the payment's hash, then clear `hide-tx` after 5 s.
  - Settle with `l1Confirmations: -1` → `confirmed`, and the tx is on chain.
  - Today it returns `settlement_pending` immediately.
- `SettlementSemanticsE2ETest#mempoolPolicyStillWaitsForInclusionWhenMempoolAcceptanceIsOff` must keep passing unchanged.

## Proof commands
- red-first (unit): `~/.claude/bin/red-first.sh 8b481e2 --baseline './gradlew compileTestJava' src/test/java/org/cardanofoundation/x402/facilitator/service/settlement/SettlementMempoolDepthTest.java -- ./gradlew test --tests '*SettlementMempoolDepthTest'`
- red (e2e): before the fix, `e2e/run.sh --reuse --tests '*MempoolEvidenceE2ETest'` → fails with `settlement_pending`; output logged verbatim
- green: `./gradlew test` green; after the fix, `e2e/run.sh` (fresh image) → green
- scope: per-batch scope file

## Log
- 2026-11-05: built by the orchestrator (R2). The unit part was done in a scratch copy of the working tree while WP-11 was running, and copied in after the e2e red proof.
- **Fix:** `SettlementService.java:119-121` now reads `int awaitDepth = config.acceptMempool() ? depth : Math.max(0, depth)` and passes `awaitDepth` to `awaitInclusion`. Nothing else changes: `observe()`, `resume()` and the journal are untouched, and the stored `selected_confirmations` stays as requested.
- **Unit test `SettlementMempoolDepthTest`:** a `RecordingChain` extends `FakeChainService`, records every `minDepth` it is awaited at, and returns `Mempool` when `minDepth <= -1` (or always, if `neverIncluded`) and `Included(0)` otherwise. `includedDepth = MEMPOOL`. Four cases:
  - main: no acceptance, -1 → confirmed with 0, `[0]`, one submit, CONFIRMED, stored -1;
  - mempool only → pending -1, NOT_CONFIRMED;
  - `requirements(1)` → `[1]`;
  - acceptance on, -1 → "mempool", await never called.
- **Unit red-first:** `red-first.sh 8b481e2 --baseline './gradlew compileTestJava' …SettlementMempoolDepthTest.java -- ./gradlew test --tests '*SettlementMempoolDepthTest'` printed "red-first: shown red at 8b481e2 … 4 tests completed, 1 failed". Per case at base: the main case fails at `SettlementMempoolDepthTest.java:97` (`success` is false), and the three guards pass.
- **Unit green:** `./gradlew test` in the tree: "BUILD SUCCESSFUL in 52s". In the copy, 380 tests ran with 0 failures. All 9 `ClientSubmissionTest` cases pass, including `minusOneWithoutOperatorOptInStillRequiresCanonicalInclusion`, which is unchanged.
- **E2E `MempoolEvidenceE2ETest`** (`@Isolated`): it registers the shim faults `mempool` and `hide-tx` on the payment hash and deletes `hide-tx` after 5 s. It then settles on the default instance with `l1Confirmations: -1`, and asserts `confirmed`, confirmations ≥ 0, elapsed ≥ 5 s (it answered only after the tx became visible), the tx on chain and submit count 1.
- **E2E red** (unfixed image, `e2e/run.sh --tests '*MempoolEvidenceE2ETest'`): `{"success":false,"errorReason":"settlement_pending",…"extra":{…"confirmations":-1,"status":"pending"}} Expecting value to be true but was false at MempoolEvidenceE2ETest.java:76`, BUILD FAILED in 15s. It answered pending immediately, as the brief predicted.
- **E2E green** (fixed image, full fresh `e2e/run.sh`): "BUILD SUCCESSFUL in 4m 5s", 156 Java tests passed and 0 failed, TS suite green, "=== e2e: PASSED". Both `MempoolEvidenceE2ETest` and `SettlementSemanticsE2ETest#mempoolPolicyStillWaitsForInclusionWhenMempoolAcceptanceIsOff` pass.
- **Red-first, verbatim** (scratchpad log, the first two lines are the script's whole verdict): "red-first: shown red at 8b481e2 ('./gradlew test --tests *SettlementMempoolDepthTest'):" / "4 tests completed, 1 failed".
- **Review** (code-reviewer): Part A Spec PASS and Quality PASS. Part B (the DevKit fix) PASS. Fix round 1, done by the orchestrator:
  - **I1** — the acceptance-on guard couldn't catch an unconditional fix, because the early return at :112 hides it. Added `withMempoolAcceptanceTheWaitStillUsesTheRequestedDepth`: `RecordingChain` raises the stored `selected_confirmations` to 0 on submit, as a concurrent retry can, so :112 is skipped and the wait runs; it must await `[-1]`. Proofs:
    - at base (`SettlementService` at HEAD in a scratch copy): new case PASS, main case FAIL, other guards PASS;
    - with the fix: `SettlementMempoolDepthTest` 5/5 and `ClientSubmissionTest` 9/9, "BUILD SUCCESSFUL";
    - mutant `int awaitDepth = Math.max(0, depth)` (unconditional): "SettlementMempoolDepthTest > withMempoolAcceptanceTheWaitStillUsesTheRequestedDepth() FAILED", "5 tests completed, 1 failed".
  - **I2** — scope: `check-scope.sh` against the run's batch scope file (every package's Allowed files, plus the ruling's `e2e/devnet/entrypoint.sh`) printed "in scope: 52 changed file(s) match 45 glob(s)". `entrypoint.sh` is outside this package's Allowed files. The orchestrator's ruling (WP-13 closing note, final report) covers it, and it is reviewed here as Part B.
  - **M3** (ruling) — the fix keeps the brief's fix site and awaits the *requested* depth. If a concurrent retry has raised the stored depth, the fresh settle can answer pending at depth 0 before the timeout. That is safe (pending, never a wrong success, no rebroadcast), and a retry resolves it.
  - **M4** — noted, no change: a lost CAS at :107 now waits at depth 0 when acceptance is off. That is harmless, because the write is fenced and there is no rebroadcast.
  - **E2E minor** — `MempoolEvidenceE2ETest` keeps the un-hide `ScheduledFuture` and calls `get()` when it is done, so a failed `deleteFault` surfaces as itself, not as `settlement_pending`. `compileE2eTestJava` passes. Re-run: `E2E_SKIP_BUILD=1 e2e/run.sh --tests '*MempoolEvidenceE2ETest' --tests '*SettlementSemanticsE2ETest'` → "MempoolEvidenceE2ETest > mempoolEvidenceAloneDoesNotAnswerAMinusOnePolicyWithoutMempoolAcceptance() PASSED", 13 passed in total, "BUILD SUCCESSFUL in 1m 9s", "=== e2e: PASSED".
- **Found on the way:** the first fresh boots of this package's runs hit an intermittent DevKit self-kill. The root cause is a stale `yaci-cli.pid` baked into the DevKit image; it is fixed in `e2e/devnet/entrypoint.sh`, an orchestrator ruling outside this package (see WP-13's closing note and the final report).
