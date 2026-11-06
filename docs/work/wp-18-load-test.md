# WP-18: One load test checks settlement invariants under concurrency at PR and nightly scale, and reports latency
Tier: R1
Base: 8b481e2
Check: `e2e/run.sh` green with `LoadE2ETest` at `E2E_LOAD_SCALE=pr` (≤ 2 min) and at `nightly`; both write `e2e/.out/load/summary.json` + `summary.md`

## Allowed files
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/LoadE2ETest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/Load.java
- docs/work/wp-18-load-test.md

## Rules
- **Placement:** `@Isolated` and part of `e2eTest`, so there's no tag, task or flag. The scale comes from the env var `E2E_LOAD_SCALE` (`pr` default, `nightly`), which Gradle's test JVM inherits.
- **Depends on:** WP-14 for `kit/Stack.java` (used, not modified); WP-13 for the shim profile, which exempts `POST */tx/submit` from its rate limit; WP-11/12 for the `Case` rows.
- **Profiles** (Java constants per scale, not files):
  - **Verify burst**: one valid payment (verify never spends), shim profile `latencyMs: 150, ratePerSec: 10, burst: 500`. Concurrency levels: `pr` 1/8/32 with 1 round; `nightly` 1/4/8/16/32/64/128 with 3 rounds.
  - **Settle mix**: weighted `Case` rows whose `expect` is `SETTLED` (default ADA, token, Masumi lock, shape rows), at a fixed arrival rate, with 5 % duplicates of already-settled payloads. `pr`: about 20 payments; `nightly`: several hundred and ≤ 15 min.
- **Pre-generation:** all payments are funded through `Funding` and built before timing starts; nothing is built inside the measured window.
- **Invariants** (the only things that fail the test):
  - every `success: true` hash is on chain exactly once (Yano direct);
  - shim submit count ≤ 1 per hash;
  - duplicates never cause a second broadcast;
  - after the run plus one reconciler sweep (≥ 35 s), every one of **this test's own** hashes that is on chain has reached `CONFIRMED` in `facilitator.settlement` (read via `Stack.exec`, filtered to the load run's hashes; the table also holds rows from every other class on the shared stack).
  - This test's rows left `CLAIMED`, `SUBMITTING` or `NOT_CONFIRMED` for hashes **not** on chain are **reported, not failed**. An Unknown submit outcome deliberately leaves a row SUBMITTING (SettlementService.java:83-87, 99-100), and with a Blockfrost-compatible provider it can never expire (see WP-13 case 3).
- **Report** (never fails the test): `summary.json` + `summary.md` with p50/p95/p99 per endpoint and level, counts per error code, readiness DOWN samples, the scale and the shim profile used.

## Proof commands
- red-first: n/a (load harness). Show the invariant checks fire by pointing one at a hash broadcast twice in a scratch run; log it, and don't keep it.
- `e2e/run.sh --reuse --tests '*LoadE2ETest'` → green at `pr` scale within 2 min; attach `summary.md` to the Log
- `E2E_LOAD_SCALE=nightly e2e/run.sh --reuse --tests '*LoadE2ETest'` → green; attach `summary.md`
- full `e2e/run.sh` → green; record how much `LoadE2ETest` adds to the PR path
- scope: per-batch scope file

## Log
### WP-18 implementation log

**Changed**
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/LoadE2ETest.java (new): one @Isolated @StackRequired test, `settlementInvariantsHoldUnderConcurrencyAndLatencyIsReported`. Phases: fund + pre-generate, settle mix, verify burst, 35 s sweep, invariants; the summary is written in a finally.
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/Load.java (new): scales (PR / NIGHTLY constants, `E2E_LOAD_SCALE`), profile constants, latency + answer-code Recorder, summary.json / summary.md writer.
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/Funding.java (file added by ruling): `wallets(int n, long ada)` (pooled wallets first, the rest from ONE top-up and chunked fan-outs; `wallet(long)` unchanged); the next chunk now waits for the previous chunk's output to be queryable; `largestFanOut()` records the measured tx size.

**Measured fan-out tx:** 100 outputs = 6730 bytes against max_tx_size 16384; MAX_OUTPUTS_PER_TX stays 100. Nightly funded 301 wallets as 100+100+100+1 outputs, so the chunk loop ran and worked.

**Mix.** Rows and weights (share of payments; every payer is a 40 ADA Funding wallet):
- default ADA transfer 45 (ttl 550 slots)
- default native-token transfer 15 (mint per payer, ttl 550)
- Masumi ADA lock 10, Masumi token lock 5 (each lock gets a unique seller nonce, because terms are claimable once)
- the five SETTLED ShapeMatrix rows, 5 each: multi-input nonce 2nd input; enterprise-address payer; payTo output also carries another token; uppercase payload nonce; script payment with a datum hash. None was left out.
- pr = 20 payments (9/3/2/1/1x5) at 2/s; nightly = 300 payments (135/45/30/15/15x5) at 1.5/s.
- Duplicates: every 20th arrival slot re-sends a random already-successful payment (pr 1, nightly 15).
- Shape bases touch chain state (token mint, second UTxO, enterprise top-up); all are built in pre-generation on 8 threads, kept in completion order so first built = first sent (Masumi and shape payments carry the default 300-slot ttl). `ShapeMatrixE2ETest.stack()` is called once to set the statics those bases read (package-private static; LoadE2ETest is @Isolated).

**Proof lines**
- pr: `LoadE2ETest > settlementInvariantsHoldUnderConcurrencyAndLatencyIsReported() PASSED`, `BUILD SUCCESSFUL in 1m 13s` (whole Gradle run including compile; test body 71 s: pregen 4 s, mix 13 s, burst 12 s, sweep 35 s). Limit 2 min.
- nightly: `... PASSED`, `=== e2e: PASSED`, 8m17,86s total wall (test body 495 s: pregen 62 s, mix 212 s, burst 156 s, sweep 49 s). Budget 15 min.
- full fresh `E2E_SKIP_BUILD=1 e2e/run.sh`: `=== e2e: PASSED`, 10m14s wall; 177 PASSED lines = 176 Java tests (junit XML sum 176) plus the TS suite line; 0 FAILED / SKIPPED. LoadE2ETest time="75.161" in the XML.
- PR-path cost: about 75 s (the test body is 71 s, of which 35 s is the mandated sweep wait). It is @Isolated, so it runs serially after the parallel classes.
- scope: check-scope lists the earlier WPs' staged files (the index is the baseline) as out of scope; LoadE2ETest.java and Load.java are not listed; Funding.java is listed only because the brief's file list predates the ruling.

**Invariant-fire evidence** (scratch edit, reverted; `grep -c SCRATCH` on LoadE2ETest.java = 0). The edit POSTed one mix payment's bytes a second time to the shim's submit path and added a never-broadcast hash to the success set. Verbatim from the run:
```
LoadE2ETest > settlementInvariantsHoldUnderConcurrencyAndLatencyIsReported() FAILED
    java.lang.AssertionError: [settlement invariants (see e2e/.out/load/summary.md)]
    Expecting empty but was: ["success:true but not on chain (Yano direct): abababababababababababababababababababababababababababababababab",
        "hash broadcast 2 times (>1): 214048c7cff2f409976f082b7ba944ba4ea1da5a1f72d48bf561395a1a28a306 [uppercase payload nonce]"]
```
The fourth invariant (on chain but not CONFIRMED after the sweep) was not fired in a scratch run; it uses the same violation list but is unproven to fire.

**summary.md, pr**
```
# Load summary

- scale: pr
- shimProfile: {"latencyMs":150,"ratePerSec":10.0,"burst":500,"appliesTo":"verify burst only; the settle mix runs with no profile"}
- fundingFanOut: 30 outputs = 2176 bytes (max_tx_size 16384)
- mix: default ADA transfer 9, default native-token transfer 3, Masumi ADA lock 2, Masumi token lock 1, and 1 each of the 5 shape rows
- pregenerationSeconds: 4
- mixSeconds: 13
- arrivalsPerSecond: 2.0
- duplicateSlots: 1
- burstSeconds: 12
- settlementRows: {"ownHashes":20,"onChain":20,"onChainAndConfirmed":20,"notOnChainByStatus (reported, not failed)":{}}
- sweepSeconds: 35
- totalSeconds: 71
- readiness: {"samples":71,"downSamples":0,"downByPhase":{}}
- violations: []

| series | n | p50 | p95 | p99 | max | answers |
|---|---|---|---|---|---|---|
| settle | 20 | 3122 | 3183 | 3571 | 3571 | {"success":20} |
| settle-duplicate | 1 | 53 | 53 | 53 | 53 | {"success":1} |
| verify@L1 | 4 | 497 | 539 | 539 | 539 | {"valid":4} |
| verify@L32 | 128 | 1982 | 2154 | 2320 | 2993 | {"valid":128} |
| verify@L8 | 32 | 524 | 539 | 539 | 539 | {"valid":32} |
```
(The mix line is abbreviated here; the file has the per-row JSON.)

**summary.md, nightly**
```
- scale: nightly
- fundingFanOut: 100 outputs = 6730 bytes (max_tx_size 16384)
- mix: default ADA 135, native token 45, Masumi ADA 30, Masumi token 15, 15 each of the 5 shape rows
- pregenerationSeconds: 62
- mixSeconds: 212
- arrivalsPerSecond: 1.5
- duplicateSlots: 15
- burstSeconds: 156
- settlementRows: {"ownHashes":300,"onChain":300,"onChainAndConfirmed":300,"notOnChainByStatus (reported, not failed)":{}}
- sweepSeconds: 49
- totalSeconds: 495
- readiness: {"samples":463,"downSamples":70,"downByPhase":{"burst":68,"recovery":2}}
- violations: []

| series | n | p50 | p95 | p99 | max | answers |
|---|---|---|---|---|---|---|
| settle | 300 | 3160 | 4211 | 5174 | 5425 | {"success":300} |
| settle-duplicate | 15 | 95 | 240 | 240 | 240 | {"success":15} |
| verify@L1 | 24 | 532 | 551 | 553 | 553 | {"valid":24} |
| verify@L128 | 3072 | 155 | 2519 | 3794 | 5600 | {"exact_cardano_facilitator_chain_lookup_failed":3065,"valid":7} |
| verify@L16 | 384 | 324 | 1130 | 2715 | 2818 | {"exact_cardano_facilitator_chain_lookup_failed":375,"valid":9} |
| verify@L32 | 768 | 635 | 1921 | 3410 | 4044 | {"exact_cardano_facilitator_chain_lookup_failed":756,"valid":12} |
| verify@L4 | 96 | 513 | 872 | 1842 | 1842 | {"valid":96} |
| verify@L64 | 1536 | 1290 | 2698 | 3913 | 5291 | {"exact_cardano_facilitator_chain_lookup_failed":1518,"valid":18} |
| verify@L8 | 192 | 508 | 1536 | 2025 | 2094 | {"exact_cardano_facilitator_chain_lookup_failed":44,"valid":148} |
```

**Notes / concerns**
- Verify burst at nightly L8 and above is mostly `exact_cardano_facilitator_chain_lookup_failed` under the 10/s, burst 500 profile (reported, as expected); readiness went DOWN in 70 of 463 samples (68 in the burst, 2 right after it).
- Duplicates were always answered `success` (idempotent) and never re-broadcast; no own row was left unresolved off chain.
- Nightly depends on the 300-slot ttl of the Masumi/shape payments: arrival (1.5/s) is set so the last send is ~212 s + pregen after the first build. A slower nightly stack could push payments past ttl and show as `ttl_expired` in the report, not as a failure.
- A fixed 35 s `Thread.sleep` is used for the sweep (the brief's wait), the only non-polling wait.
- Funding the nightly mix takes 40 ADA x 301 from the faucet in one top-up; it worked on this DevKit.
- Stack was torn down by the final run.sh.

### Fix round 1
The builder made the code changes on 2026-11-05. It was stopped before running any proof. On 2026-11-06 the orchestrator ran the proofs, made the N1 change and wrote this Log.

**Changes (review findings → fix)**
- **C1:** `checkDatabase` now records a violation if no psql read ever succeeds. In that case `lastRead` stays null, and the violation names the cause `Poll` kept.
- **I2:** writing the summary is wrapped in a try/catch, so the report never fails or masks the test.
- **I3:** every `success: true` hash must have a shim submit count of exactly 1, not just ≤ 1. This also proves the counter sees the facilitator's own submits.
- **I4 (ruling):** these are harness errors and fail the test:
  - no successful original settle;
  - no duplicate sent;
  - any `ttl_expired` answer.

  If no duplicate slot fired during the mix, one duplicate of a settled payment is sent after it. The oldest payment's age at send time is in the summary.
- **N1 (scoped re-review):** the `ttl_expired` check ran before the verify burst, so expiries during the burst could never fail the test. The check now runs in the `finally` block, after the burst and the sweep. The summary gains `ttlExpiredAnswers`.
- **Minors:**
  - Funding's `max_tx_size` log read can no longer fail funding.
  - The on-chain check uses one shared 120 s deadline.
  - Invariant 4's on-chain set includes the success hashes already proven on chain.
  - The burst payment is built in pre-generation.
- **Not requested by the review:**
  - The nightly arrival rate is now 2.0/s (was 1.5/s). The Masumi and shape payments carry the default 300-slot validity and are all built before the mix, so a faster rate sends the oldest one younger. Measured: 167 s old at send.
  - The `Load.NIGHTLY` Javadoc now gives that reason.
- **Correction to the log above (minor 10):** not every payer is a 40 ADA Funding wallet. The enterprise-address row pays from a fresh enterprise account that DevKit tops up with 20 ADA (`Fixtures.enterpriseWallet(kit, 20)`, called in pre-generation).

**Proofs.** The images were current: no source was newer than `x402-facilitator:e2e`, and the DevKit image contains the stale-pid fix. The stack was booted with `E2E_SKIP_BUILD=1 e2e/run.sh --up`.
- pr: `E2E_LOAD_SCALE=pr e2e/run.sh --reuse --tests '*LoadE2ETest'` gave `LoadE2ETest > settlementInvariantsHoldUnderConcurrencyAndLatencyIsReported() PASSED`, `BUILD SUCCESSFUL in 1m 20s` and `=== e2e: PASSED`. The test body took 77 s, inside the 2 min limit.
- C1 fire-proof, a scratch edit that was reverted: the query was pointed at the nonexistent table `facilitator.settlement_SCRATCH`. Afterwards `cmp` against the backup reported no difference and `grep -c SCRATCH` = 0. Verbatim:
  ```
  LoadE2ETest > settlementInvariantsHoldUnderConcurrencyAndLatencyIsReported() FAILED
      java.lang.AssertionError: [settlement invariants (see e2e/.out/load/summary.md)]
      Expecting empty but was: ["database check did not complete: java.lang.AssertionError: timed out after 90 s waiting for every on-chain hash of this run CONFIRMED (cause: java.lang.IllegalStateException: [docker, compose, -p, x402-e2e, -f, …/e2e/compose.e2e.yml, exec, -T, postgres, psql, -U, postgres, -d, postgres, -At, -c, sel…
  BUILD FAILED in 2m 49s
  ```
- nightly: `E2E_LOAD_SCALE=nightly e2e/run.sh --reuse --tests '*LoadE2ETest'` gave `… PASSED`, `BUILD SUCCESSFUL in 7m 27s` and `=== e2e: PASSED`. This ran on the code before the N1 move. The run has 0 `ttl_expired` answers in every series, so the moved check would not have fired.
- `./gradlew test`: `BUILD SUCCESSFUL in 42s`. Teardown: `down -v`, then 0 x402-e2e containers.
- One nightly settle answered `settlement_pending`; its hash then reached CONFIRMED through the reconciler (300/300 on chain and CONFIRMED). This is reported, not failed, as the brief requires.

**summary.md, pr (verbatim)**
```
# Load summary

- **scale**: `pr`
- **shimProfile**: `{"latencyMs":150,"ratePerSec":10.0,"burst":500,"appliesTo":"verify burst only; the settle mix runs with no profile"}`
- **fundingFanOut**: `30 outputs = 2176 bytes (max_tx_size 16384)`
- **mix**: `{"default ADA transfer":{"weight":45,"payments":9},"default native-token transfer":{"weight":15,"payments":3},"Masumi ADA lock":{"weight":10,"payments":2},"Masumi token lock":{"weight":5,"payments":1},"multi-input, nonce is the second input":{"weight":5,"payments":1},"enterprise-address payer":{"weight":5,"payments":1},"payTo output also carries another token":{"weight":5,"payments":1},"uppercase payload nonce":{"weight":5,"payments":1},"script payment with a datum hash":{"weight":5,"payments":1}}`
- **pregenerationSeconds**: `4`
- **mixSeconds**: `13`
- **arrivalsPerSecond**: `2.0`
- **duplicateSlots**: `1`
- **oldestPaymentAgeAtSendSeconds**: `11`
- **successfulOriginalSettles**: `20`
- **burstSeconds**: `17`
- **settlementRows**: `{"ownHashes":20,"onChain":20,"onChainAndConfirmed (last successful read)":20,"notOnChainByStatus (reported, not failed)":{}}`
- **sweepSeconds**: `36`
- **totalSeconds**: `77`
- **readiness**: `{"samples":73,"downSamples":0,"downByPhase":{}}`
- **violations**: `[]`

## Latency per endpoint and level (ms)

| series | n | p50 | p95 | p99 | max | answers |
|---|---|---|---|---|---|---|
| settle | 20 | 4361 | 7069 | 7592 | 7592 | {"success":20} |
| settle-duplicate | 1 | 186 | 186 | 186 | 186 | {"success":1} |
| verify@L1 | 4 | 540 | 591 | 591 | 591 | {"valid":4} |
| verify@L32 | 128 | 2464 | 3294 | 3318 | 3397 | {"valid":128} |
| verify@L8 | 32 | 631 | 1341 | 1979 | 1979 | {"valid":32} |
```

**summary.md, nightly (verbatim)**
```
# Load summary

- **scale**: `nightly`
- **shimProfile**: `{"latencyMs":150,"ratePerSec":10.0,"burst":500,"appliesTo":"verify burst only; the settle mix runs with no profile"}`
- **fundingFanOut**: `100 outputs = 6730 bytes (max_tx_size 16384)`
- **mix**: `{"default ADA transfer":{"weight":45,"payments":135},"default native-token transfer":{"weight":15,"payments":45},"Masumi ADA lock":{"weight":10,"payments":30},"Masumi token lock":{"weight":5,"payments":15},"multi-input, nonce is the second input":{"weight":5,"payments":15},"enterprise-address payer":{"weight":5,"payments":15},"payTo output also carries another token":{"weight":5,"payments":15},"uppercase payload nonce":{"weight":5,"payments":15},"script payment with a datum hash":{"weight":5,"payments":15}}`
- **pregenerationSeconds**: `62`
- **mixSeconds**: `160`
- **arrivalsPerSecond**: `2.0`
- **duplicateSlots**: `15`
- **oldestPaymentAgeAtSendSeconds**: `167`
- **successfulOriginalSettles**: `299`
- **burstSeconds**: `155`
- **settlementRows**: `{"ownHashes":300,"onChain":300,"onChainAndConfirmed (last successful read)":300,"notOnChainByStatus (reported, not failed)":{}}`
- **sweepSeconds**: `49`
- **totalSeconds**: `444`
- **readiness**: `{"samples":408,"downSamples":79,"downByPhase":{"burst":75,"recovery":4}}`
- **violations**: `[]`

## Latency per endpoint and level (ms)

| series | n | p50 | p95 | p99 | max | answers |
|---|---|---|---|---|---|---|
| settle | 300 | 3155 | 5106 | 5684 | 6104 | {"settlement_pending":1,"success":299} |
| settle-duplicate | 15 | 104 | 2281 | 2281 | 2281 | {"success":15} |
| verify@L1 | 24 | 501 | 510 | 654 | 654 | {"valid":24} |
| verify@L128 | 3072 | 181 | 2829 | 4287 | 5944 | {"exact_cardano_facilitator_chain_lookup_failed":3070,"valid":2} |
| verify@L16 | 384 | 321 | 663 | 969 | 993 | {"exact_cardano_facilitator_chain_lookup_failed":376,"valid":8} |
| verify@L32 | 768 | 629 | 1541 | 1986 | 2680 | {"exact_cardano_facilitator_chain_lookup_failed":761,"valid":7} |
| verify@L4 | 96 | 499 | 522 | 528 | 528 | {"valid":96} |
| verify@L64 | 1536 | 1280 | 3518 | 4400 | 5836 | {"exact_cardano_facilitator_chain_lookup_failed":1517,"valid":19} |
| verify@L8 | 192 | 497 | 656 | 1548 | 1555 | {"exact_cardano_facilitator_chain_lookup_failed":68,"valid":124} |
```
These two summaries supersede the "summary.md, pr" and "summary.md, nightly" blocks above, which were abbreviated and came from the code before this fix round.

**N1 proofs (final code).**
- pr, re-run on a fresh `--up` stack: `… PASSED`, `BUILD SUCCESSFUL in 1m 19s`, `=== e2e: PASSED`. Summary: `ttlExpiredAnswers: 0`, `totalSeconds: 77`, `violations: []`.
- N1 fire-proof, a scratch edit that was reverted: the burst payment was built with `ttlSlots(3)`, so it expires before the burst. Afterwards `cmp` reported no difference, `grep -c SCRATCH` = 0, and the restored file compiles. Verbatim:
  ```
  LoadE2ETest > settlementInvariantsHoldUnderConcurrencyAndLatencyIsReported() FAILED
      Expecting empty but was: ["HARNESS: 164 answer(s) were ttl_expired; the run is too slow for the payments' validity"]
  BUILD FAILED in 1m 2s
  ```
  164 is every verify call in the pr burst: 4 + 32 + 128.

### Rulings, re-review and closing (orchestrator)
- **I4 (ruling, 2026-11-05):** a run with no evidence cannot certify the invariants. So no successful original settle, no duplicate sent, or any `ttl_expired` answer is a harness error that fails the test. Cost if wrong: on a slow host the load test fails on TTL instead of passing silently.
- **Scoped re-review of fix round 1:**
  - ADDRESSED: C1, I2, I3 and minors 6-9.
  - Sound: the rate change (oldest send age about 210 s → about 158 s) and the forced duplicate.
  - OPEN: I4 / N1 (`expired` was checked before the burst).
- **N1 fix (R0, orchestrator):** the `ttl_expired` check moved into `finally`, and the summary gains `ttlExpiredAnswers`. The rate Javadoc was corrected. Both directions are proven above: green at 0, red at 164. There was no further scoped review; the final whole-change review covers it.
- **Builder:** stopped by the user on 2026-11-06 after making the fix-round code changes. The orchestrator ran the proofs and wrote this Log, and did not resume the builder.
- **Scope:** checked against the run's batch scope file. Verbatim: "in scope: 72 changed file(s) match 75 glob(s)" (exit 0). Before fix round 1, `check-scope` printed earlier packages' files as out of scope; that came from checking against this brief alone (see "Proof lines" above).
