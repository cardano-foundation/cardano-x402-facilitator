# WP-19: The docs describe the one way to run and the one way to write tests
Tier: R1
Base: 8b481e2
Check: every command in `docs/testing.md`'s devnet section runs as written; README and docs name `preprodE2e`; the target-state doc and STATUS reflect what was built

## Allowed files
- docs/testing.md
- README.md
- docs/plans/2026-11-04-test-pipeline-target-state.md
- docs/work/STATUS.md
- docs/work/wp-19-docs.md
- deploy/README.md
- e2e/ts/package.json
- e2e/ts/test/sdk.test.mjs
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/ChainOutageE2ETest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/StackRequired.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/E2eEnv.java
- src/test/java/org/cardanofoundation/x402/facilitator/e2e/X402PreprodE2E.java

## Rules
- **Rewrite the devnet section of `docs/testing.md`**:
  - The two commands: `./gradlew test` (no stack, includes the coverage gates) and `e2e/run.sh` (`--up` / `--keep` / `--reuse`, Gradle filters).
  - What `@Isolated` means for disruptive classes.
  - How to add a case: one `Case` row (positive shape, rejection or Masumi), with an example of each.
  - The shim fault API.
  - `E2E_LOAD_SCALE` and `E2E_CANARY`.
  - The single CI workflow and what `schedule` runs.
- Fold in the doc notes logged by WP-9 to WP-18, including the preprod task rename in README/docs.
- Set the target-state doc's status line to "implemented except …".
- Update `docs/work/STATUS.md`.
- Docs and **message-only** edits: no code or config changes.
  - In the listed test and source files, change only comments or user-facing hint strings that name removed commands. The commands are `e2e/run.sh --java`, `--ts` and `./gradlew e2e`; the reviewer found them at ChainOutageE2ETest.java:177, kit/StackRequired.java:29, kit/E2eEnv.java:56, sdk.test.mjs:5,19, e2e/ts/package.json:5, X402PreprodE2E.java:41, README.md:98,192 and deploy/README.md:241.
  - No assertion or behaviour changes (orchestrator ruling, 2026-11-04).
  - Also point any badge or doc that names `.github/workflows/e2e.yml` at `build.yml`.

## Proof commands
- red-first: n/a (docs)
- run every command in the new devnet section once against a `--reuse` stack (or `--help`/`tasks` where it only prints), and log the results
- `grep -rn "gradlew e2e\b" README.md docs/` → no stale mentions
- scope: per-batch scope file

## Log

### WP-19 builder log (2026-11-06)
Changed:
- `docs/testing.md`: devnet section rewritten (two commands + coverage gates, run.sh flags/env, class tables, how to add a Case with a shape / rejection / Masumi example, `@Isolated` + IsolatedStackGuard, shim fault API, `E2E_LOAD_SCALE`, `E2E_CANARY`, CI jobs and what `schedule` runs, Known gaps); top table and `./gradlew e2e` -> `preprodE2e`.
- `README.md`, `deploy/README.md`: `preprodE2e`; README adds `e2e/run.sh` line and docs-table row.
- `docs/plans/2026-11-04-test-pipeline-target-state.md`: status "implemented except" (no live CI run; branch protection; synthetic ledger-reject body; swallowed submit forever pending), Today line = local measurements, catalogue "new" flipped to test classes (ShapeMatrixE2ETest, MasumiTermsConflictE2ETest, ProviderFaultE2ETest, MempoolEvidenceE2ETest, CrashRecoveryE2ETest). "reappears" half exists, so it is not an exception.
- `docs/work/STATUS.md`: four lines, Updated 2026-11-06.
- Message-only: ChainOutageE2ETest.java:177, kit/StackRequired.java:29, kit/E2eEnv.java:56, sdk.test.mjs:5,19, e2e/ts/package.json:5, X402PreprodE2E.java:41; `e2e/run.sh` devkit_dead reason string (ruling 2026-11-05), nothing else.
Proofs (verbatim tails):
- `bash -n e2e/run.sh` -> ok.
- `./gradlew compileE2eTestJava compileTestJava -q` -> no output (success).
- `./gradlew test` -> `BUILD SUCCESSFUL in 42s`.
- `e2e/run.sh --reuse --tests '*ShapeMatrixE2ETest'` -> `BUILD SUCCESSFUL in 34s`, `=== e2e: PASSED`.
- shim API curls from the doc, against the live stack: POST faults `{"id": "hide"} [200]`; DELETE one `{"deleted":true} [200]`; DELETE all `{"deleted":true} [200]`; POST profile `{"ok":true} [200]`; DELETE profile `{"deleted":true} [200]`; GET submits `{"sha256": "abab…", "count": 0} [200]`.
- `E2E_SKIP_BUILD=1 E2E_LOAD_SCALE=nightly e2e/run.sh --reuse --tests '*LoadE2ETest'` -> `LoadE2ETest > settlementInvariantsHoldUnderConcurrencyAndLatencyIsReported() PASSED`, `BUILD SUCCESSFUL in 7m 14s`, `=== e2e: PASSED`.
- `grep -rn "gradlew e2e\b" README.md docs/` -> only 3 hits, all in docs/work/wp-9 and wp-19 (the notes/brief that quote the old command); none in README or user docs.
- teardown: `docker compose -p x402-e2e -f e2e/compose.e2e.yml down -v`, 0 containers left.
Not run: `e2e/run.sh` (full, ~10 min), `e2e/run.sh --up/--keep` (stack was already up via --up; ports busy) and the `E2E_CANARY=1` run (needs npm network access and rebuilds the images). Their flags are the run.sh header's, unchanged.
scope: check-scope against this brief lists earlier packages' staged files (index baseline) as out of scope; `e2e/run.sh` is outside this brief by orchestrator ruling.

### Fix round 1
The orchestrator wrote this section. The builder's hand-back said it had appended it, but the file was unchanged.
- Task review result: Spec FAIL / Quality FAIL, on one Important finding:
  - I1: the docs said `verify` is skipped on a manual dispatch with `nightly` ticked.
- Minors:
  - M1: the "Five layers" count;
  - M2: the interaction of `E2E_CANARY` and `E2E_SKIP_BUILD` was undocumented;
  - M3: "every push";
  - M4: the target-state diagram had the schedule feeding `verify`;
  - M5: newcomer prose named internal package logs;
  - M6: no verbatim scope line.
- Fixes, by the builder, in docs/testing.md and docs/plans/2026-11-04-test-pipeline-target-state.md:
  - I1: `verify` is skipped on `schedule` only (build.yml:41). A dispatch runs `verify` plus the `pr` leg; with `nightly` ticked it runs `verify` plus the `nightly` and `canary` legs and no `pr` leg (build.yml:71-73). `publish` never runs on a dispatch (build.yml:151).
  - M1: "Six layers".
  - M2: a note at both `E2E_CANARY=1` mentions. With `E2E_SKIP_BUILD=1`, the newest DevKit is not built, so the run uses the pinned DevKit with the newest SDKs (run.sh:159-173).
  - M3: the triggers are listed as build.yml defines them: push to main, develop, `release/*` and version tags, plus PRs, the schedule and manual dispatch.
  - M4: the schedule node feeds only the e2e image and stack path.
  - M5: the numbers are now given as "measured locally on 2026-11-05/06".
- Fix diff: 102 lines, two files.

### Rulings, re-review and closing (orchestrator)
- **e2e/run.sh (ruling, 2026-11-05):** one message-only change, the `devkit_dead` reason for a `Killed` line. The fresh-boot "Killed" seen on 2026-11-05 was DevKit killing its own CLI through a stale `yaci-cli.pid`, not memory. The new string names both causes and points at e2e/devnet/entrypoint.sh.
- **M5 (ruling):** newcomer-facing docs do not name internal package logs. The target-state doc's catalogue rows still name WP-15 and WP-16. That is a design record linked to the implementation plan, so the names stay.
- **Stale mentions:** they remain only in historical records: docs/work/* and the older docs/plans/2026-10-26-devnet-e2e-suite.md and implementation plan. They are kept as written.
- **Scoped re-review of fix round 1:** I1 and M1 to M5 FIXED, with no new errors. Result: "WP-19 re-review: CLEAN".
- **Commands not run by the builder:**
  - The full `e2e/run.sh` default path is covered by the final whole-change run.
  - `--up` was run repeatedly in this execution (WP-18 proofs), and `--keep` in WP-14.
  - `E2E_CANARY=1` was last run in WP-9 (Log there). It was not re-run after the docs change.
- **Scope:** checked against the run's batch scope file. Verbatim: "in scope: 72 changed file(s) match 75 glob(s)" (exit 0).
