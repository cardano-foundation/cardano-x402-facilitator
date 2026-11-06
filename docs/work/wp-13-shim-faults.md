# WP-13: Provider failure paths are tested through opt-in shim faults
Tier: R1
Base: 8b481e2
Check: `ShimFaultsE2ETest` and `ProviderFaultE2ETest` green in `e2e/run.sh`; `./gradlew test` green with the two converted coverage entries

## Allowed files
- e2e/shim/yano_genesis_shim.py
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/ShimClient.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/IsolatedStackGuard.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/ShimFaultsE2ETest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/ProviderFaultE2ETest.java
- e2e/coverage/wp-4.yml
- e2e/coverage/wp-13.yml
- docs/work/wp-13-shim-faults.md

## Rules
- **Shim control API** (handled by the shim, never forwarded):
  - `POST /__shim/faults` `{id, match: {txHash | sha256 | pathRegex | outRef}, action: {...}, times?}`. Submit-path faults match by `sha256` of the body (the test computes it from the tx bytes); deriving the tx hash from the body would need a CBOR parser, and the shim is stdlib only. Read-path faults match by `txHash` or `outRef` taken from the URL.
  - `DELETE /__shim/faults` (all) and `DELETE /__shim/faults/<id>`
  - `POST /__shim/profile` `{latencyMs, ratePerSec, burst}` and `DELETE /__shim/profile`
- **Actions**:
  - `drop-response`: forward a submit, then answer 502.
  - `swallow`: do not forward; answer 200 with the tx hash given in the action.
  - `ledger-reject`: do not forward; answer 400 with the given body.
  - `status`: answer the given status without forwarding.
  - `mempool`: `*/mempool/<txHash>` answers 200.
  - `hide-tx`: `*/txs/<txHash>` and its sub-paths answer 404. It does **not** touch `*/mempool/<txHash>`, which follows its own `mempool` fault or passes through. `ShimFaultsE2ETest` covers `mempool` + `hide-tx` on one hash (WP-15 depends on it).
  - `drop-lovelace`: in the UTxO response the facilitator's lookup reads for `outRef` (per the reviewer, `GET */txs/<hash>/utxos`; confirm in `BlockfrostChainService.openUtxoLookup`), remove the `lovelace` entry from that output's `amount`. A `null` quantity would not work: it throws "invalid provider UTxO value" (BlockfrostChainService.java:204-205) and verify answers `chain_lookup_failed`, not `input_value_unavailable`.
  - Profile: add `latencyMs` to every facilitator-view request; over `ratePerSec`/`burst`, answer 429 with a Blockfrost-shaped body. `POST */tx/submit` is exempt from the rate limit, because a 429 there is an Unknown submit outcome that correctly leaves the row SUBMITTING (SettlementService.java:83-87, 99-100).
- **Safety**:
  - Faults and the profile apply to the facilitator view only; `/sdk/*` is untouched.
  - With nothing registered, behaviour is byte-for-byte today's pass-through.
  - The submit counter keeps counting every `POST */tx/submit` body received, including faulted ones. Document that in the shim docstring.
  - stdlib only.
- **`ShimFaultsE2ETest`** (JUnit, `@Isolated`, against the live shim and Yano; no Python test runner):
  - With nothing registered, a GET through the shim's facilitator view equals Yano direct, byte for byte, on **stable** endpoints only (e.g. `/txs/<a confirmed hash>`, its `/utxos`). Never compare tip-dependent ones like `/blocks/latest`, and genesis is rewritten by design.
- **`IsolatedStackGuard`** (from WP-11): after every `@Isolated` class, also `DELETE /__shim/faults` and `DELETE /__shim/profile`, so no fault leaks into later classes.
  - Each action behaves as specified, including `mempool` + `hide-tx` together, `times`, delete one/all, the profile's 429, the submit exemption, and `/sdk` ignoring faults.
  - Faults are cleared in `finally`.
- **ProviderFaultE2ETest**: `@Isolated` (it uses path-wide faults and the profile). Every test clears its faults in `finally`. Each case cites its code; if the facilitator disagrees, **stop and report**:
  1. `drop-response` on submit → settle `settlement_pending` (SettlementService.java:99-100); the tx still lands; re-settle → `confirmed`; submit count stays 1 (SettlementService.java:137-142).
  2. `ledger-reject` with a real cardano-node body containing `ApplyTxError` (BlockfrostChainService.java:239-243) → `settlement_definitively_rejected`; re-settle → the same (SettlementService.java:133-135); tx never on chain.
  3. `swallow` → settle `settlement_pending`; re-settle → still `settlement_pending`; submit count stays 1 (received, never forwarded) and the tx is never on chain. Assert **nothing about expiry**: with a Blockfrost-compatible provider, `NotSeen` carries no watermark (`observedThroughSlot = -1`, InclusionResult.java:9), so neither `expireIfProven` (SettlementService.java:216-218) nor the reconciler (SettlementReconciler.java:135-137) can ever prove expiry. That stuck row is an open finding for the user, not a requirement to pin. The first settle blocks for the full confirmation timeout (75 s, X402Properties.java:52), so size the HTTP client timeout above it. The row stays due and both reconcilers poll it every 30 s for the rest of the run; note that in the Log.
  4. `drop-lovelace` on the nonce outRef → verify `invalid_exact_cardano_payload_input_value_unavailable` (ExactCardanoScheme.java:238).
  5. `hide-tx` after `confirmed` → re-settle `settlement_pending` and no rebroadcast (SettlementService.java:190-193); clear → re-settle `confirmed`.
  6. `status 429` on the nonce's UTxO lookup path → verify `chain_lookup_failed`.
- **Not testable, by design: `SETTLEMENT_FAILED` (NotSubmitted).** It needs the submit-time network-identity or tip check to fail (BlockfrostChainService.java:221-226). Verify itself calls `/blocks/latest` on its first lookup (BlockfrostChainService.java:110-112), and both instances' reconcilers call it every 30 s (SettlementReconciler.java:55, 97), so a fault on only the submit-time call is racy. Keep the exclusion, and replace its reason in `wp-4.yml` with this precise one.
- **Coverage**: in `wp-4.yml`, turn `INPUT_VALUE_UNAVAILABLE` and `SETTLEMENT_DEFINITIVELY_REJECTED` from `excluded` into `tests:` referencing these methods; update `SETTLEMENT_FAILED`'s reason as above. Any new code mapping goes to `wp-13.yml`.

## Proof commands
- red-first: n/a (characterization of existing behaviour; shim feature is new)
- the shim change needs a fresh stack: `e2e/run.sh --keep --tests '*ShimFaultsE2ETest'` → green
- `e2e/run.sh --reuse --tests '*ProviderFaultE2ETest'` → green, then full `e2e/run.sh` → green (no other class affected)
- `./gradlew test` → green (coverage gate)
- scope: per-batch scope file

## Log

### Implementer log (WP-13)
**Changed**
- `e2e/shim/yano_genesis_shim.py`: fault + profile control API (POST/DELETE `/__shim/faults[/<id>]`, POST/DELETE `/__shim/profile`), actions drop-response, swallow, ledger-reject, status, mempool, hide-tx, drop-lovelace; latency; token-bucket 429 only when `ratePerSec` given; submit exempt from the rate limit; `/sdk/*` untouched; submit counter still counts every body (documented in the docstring); non-shim DELETE still 501 as before. stdlib only.
- `kit/ShimClient.java`: public `registerFault` (with/without times), `deleteFault`, `clearFaults`, `setProfile(latencyMs, ratePerSec, burst)` (nullable fields), `clearProfile`, `reset`; `sha256Hex` made public.
- `kit/IsolatedStackGuard.java`: after every `@Isolated` class first `ShimClient.reset()` (DELETE faults + profile), then the existing both-READY wait.
- `ShimFaultsE2ETest.java` (13 tests, `@Isolated`), `ProviderFaultE2ETest.java` (6 tests, `@Isolated`).
- `e2e/coverage/wp-4.yml`: INPUT_VALUE_UNAVAILABLE and SETTLEMENT_DEFINITIVELY_REJECTED now `tests:`; SETTLEMENT_FAILED reason replaced. No `wp-13.yml` needed (no new code mapping).

**Proofs (verbatim)**
- fresh stack, `e2e/run.sh --keep --tests '*ShimFaultsE2ETest'`: 13 x PASSED, `BUILD SUCCESSFUL in 14s`, `=== e2e: PASSED`, wall 1:08,29
- `e2e/run.sh --reuse --tests '*ProviderFaultE2ETest'`: 6 x PASSED, `BUILD SUCCESSFUL in 1m 33s`, `=== e2e: PASSED`, wall 1:34,15
- `./gradlew test`: `BUILD SUCCESSFUL in 41s` (ErrorCodeCoverageTest included)
- full `e2e/run.sh --reuse`: 155 PASSED test lines (136 + 19), 0 FAILED/SKIPPED, `BUILD SUCCESSFUL in 4m 2s`, TS suite `tests 6 / pass 6 / fail 0`, `=== e2e: PASSED`
- stack torn down (`docker compose -p x402-e2e ... down -v`).
- check-scope.sh: lists 42 files OUT OF SCOPE; these are the earlier packages' staged changes (the index starting point). My own changes are exactly the allowed files (unstaged/untracked: wp-4.yml, shim, IsolatedStackGuard, ShimClient, the two new tests).

**Swallow-row note**: `aSwallowedSubmitStaysPendingAndIsNeverRebroadcast` leaves a pending row that can never expire (NotSeen has no watermark, InclusionResult.java:9); it stays due and both reconcilers poll it every 30 s for the rest of the run. Open finding for the user, not pinned. The first settle blocks the full 75 s confirmation timeout (suite client timeout is 180 s).

**drop-lovelace path (confirmed)**: `GET */txs/<hash>/utxos` (cardano-client `getTxOutput` -> `TransactionApi @GET("txs/{hash}/utxos")`, used by BlockfrostChainService.openUtxoLookup). Proved empirically: the fault on `outRef=<nonce>` turns a valid verify into `exact_cardano_facilitator_input_value_unavailable`; the control verify is valid before and after.

**ledger-reject body**: NOT captured from a real node. Yano answers junk with `{"error":"Failed to submit transaction: Mempool admission failed (MALFORMED)..."}` (no ApplyTxError text) and the DevKit container has no cardano-node/cli. The body used is the Blockfrost-wrapped cardano-node shape reproduced from memory of public reports: `{"error":"Bad Request","message":"\"transaction submit error ShelleyTxValidationError ShelleyBasedEraConway (ApplyTxError (ConwayUtxowFailure (UtxoFailure (BadInputsUTxO (fromList [TxIn (TxId {unTxId = SafeHash \"0d4f...\"}) (TxIx {unTxIx = 0})])))))\"","status_code":400}` (hash invented). Swap in a captured body if one exists.

**Assumed / Unsure / Skipped**
- The brief's code `invalid_exact_cardano_payload_input_value_unavailable` is wrong: ErrorCodes.INPUT_VALUE_UNAVAILABLE is `exact_cardano_facilitator_input_value_unavailable` (returned at ExactCardanoScheme.java:238); the test asserts the real value. All six provider cases matched the facilitator's behaviour; no BLOCKED.
- `outRef` on a `status` fault matches the whole `/txs/<hash>/utxos` (URL has no index) and a funding fan-out tx is shared by up to 30 wallets; safe only because ProviderFaultE2ETest is `@Isolated`.
- Skipped: SETTLEMENT_FAILED (by design; wp-4.yml reason updated).

### Fix round 1
1. Swallow citation: ProviderFaultE2ETest case 3 now cites SettlementService.java:102-121, 197-203 (Accepted -> SUBMITTED -> NOT_CONFIRMED -> pending; resume lands in the same branch).
2. ShimFaultsE2ETest compares status only on Yano 404s (/mempool/<confirmed>, /txs/000..0); byte equality kept on the stable 200 endpoints.
3. Shim fault validation: every match value must be str; swallow needs str txHash, ledger-reject needs body, status needs a non-bool int status; else 400. aMalformedFaultOrProfileIsRefused gained txHash:123, swallow w/o txHash, ledger-reject w/o body, status w/o status, status "429", and asserts nothing malformed got in.
4. IsolatedStackGuard: reset() exception is kept, both facilitators are always awaited READY, then it is rethrown (suppressed onto a readiness failure if both fail).
5. Rate-limit test: first request must be 200, a 429 must appear within 40 rapid requests, body Blockfrost-shaped (status_code, error, message); submit and /sdk exemptions kept.
6. aMalformedFaultOrProfileIsRefused now resets in finally.
7. ShimClient.deleteFault URL-encodes the id (spaces as %20).

Proof (verbatim): fresh stack ShimFaultsE2ETest 13 x PASSED, BUILD SUCCESSFUL in 13s, e2e: PASSED (wall 1:12); ProviderFaultE2ETest 6 x PASSED, BUILD SUCCESSFUL in 1m 32s; full e2e/run.sh --reuse 155 PASSED, BUILD SUCCESSFUL in 4m 16s, TS tests 6 / pass 6 / fail 0, e2e: PASSED; ./gradlew test BUILD SUCCESSFUL in 54s; stack torn down.

### Re-review and closing change (orchestrator)
- Scoped re-review of fix round 1: items 1–7 are ADDRESSED and the pass-through is unchanged. "WP-13 re-review: CLEAN".
- Applied the re-review's second optional point: `ShimClient.reset()` now clears the profile in `finally`. Before, a failed `clearFaults()` could leave a rate limit in place that stalls IsolatedStackGuard's readiness wait. `compileE2eTestJava` exits 0. WP-15's full run also exercises this, since the guard resets after every `@Isolated` class.
- Parked as optional: the "nothing malformed got in" probe would be stronger if it matched `confirmedHash`.
- Seen during WP-15's red run on 2026-11-05: one fresh DevKit boot died 10 s in. DevKit printed "Killing existing yaci-cli process and its subprocesses with PID: 30", then bash reported "9 Killed yaci-devkit up". No other containers were running, so DevKit killed its own CLI; memory was not the cause. WP-9's check failed the run after 10 s instead of hanging, and the retry booted cleanly. run.sh's hint "likely out of memory" is misleading for this case, so WP-19 rewords it.
