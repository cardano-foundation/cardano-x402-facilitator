# WP-17: Masumi terms reuse and CORS are covered on the devnet
Tier: R1
Base: 8b481e2
Check: `MasumiTermsConflictE2ETest` and `CorsE2ETest` green in `e2e/run.sh`

## Allowed files
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/MasumiTermsConflictE2ETest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/CorsE2ETest.java
- e2e/compose.e2e.yml
- e2e/coverage/wp-17.yml
- e2e/coverage/wp-3.yml
- docs/work/wp-17-single-cases.md

## Rules
- These are scenario tests (two transactions; HTTP headers), not single-transaction rows, so they are plain JUnit methods. Any transaction they build goes through `PaymentFactory`, `kit/MasumiLock` (moved in WP-11) and `Funding`.
- **MasumiTermsConflictE2ETest**:
  - Setup: two transactions from two `Funding` wallets lock into the escrow under the **same** seller-signed terms.
  - The first settles `confirmed`.
  - The second settle → `duplicate_settlement` with the message `Masumi terms already claimed by another transaction` (SettlementService.java:73; unique `terms_digest`, ADR 2026-10-09). Its submit count is 0 and it is never on chain.
  - Append the test to `DUPLICATE_SETTLEMENT`'s existing `tests:` list in `wp-3.yml`.
- **CORS**:
  - `facilitator-mempool` gets `X402_HTTP_CORSALLOWEDORIGINS: https://allowed.e2e.test` (relaxed binding to `X402Properties.Http.corsAllowedOrigins`; there is no `x402.http` block in application.yml). Confirm the binding took effect; log how.
  - `CorsE2ETest`: a preflight `OPTIONS /verify` and `/settle` with that origin → `Access-Control-Allow-Origin` equals it, methods include POST; `/supported` allows GET (CorsConfig.java:26-31).
  - Another origin → no `Access-Control-Allow-Origin`.
  - The default instance (no origins, CorsConfig.java:26) → no `Access-Control-Allow-Origin` for any origin.
  - Record the exact status Spring returns for rejected preflights in the Log, and assert the header, not the status.
- No other compose change. Other test classes must not depend on CORS.

## Proof commands
- red-first: n/a (characterization)
- the compose change needs a fresh stack: `e2e/run.sh --keep --tests '*MasumiTermsConflictE2ETest' --tests '*CorsE2ETest'` → green
- full `e2e/run.sh` → green, Masumi count unchanged
- `./gradlew test` → green (coverage gate)
- scope: per-batch scope file

## Log

### Implementation (WP-17)
Files:
- src/e2eTest/.../e2e/MasumiTermsConflictE2ETest.java (new): `aSecondTransactionUnderAlreadyClaimedTermsIsADuplicateSettlement`. Two `Funding.wallet(30)` wallets, two `MasumiLock` builds; the first uses a random 32-byte seller nonce (unique per run), the second copies every term from the first via `preSign`, so the seller signature is identical. First: `assertVerifiedAndSettled`. Second /settle: `duplicate_settlement`, message `Masumi terms already claimed by another transaction`, `transaction` empty, `submitCount` 0, `isOnChain` false.
- src/e2eTest/.../e2e/CorsE2ETest.java (new): 5 tests (`aConfiguredOriginMayPostToVerify`, `...PostToSettle`, `...GetSupported`, `anotherOriginGetsNoAllowOriginHeader`, `theDefaultInstanceSendsNoAllowOriginHeaderToAnyOrigin`). Header asserted, never the status.
- e2e/compose.e2e.yml: only `X402_HTTP_CORSALLOWEDORIGINS: https://allowed.e2e.test` on `facilitator-mempool`.
- e2e/coverage/wp-3.yml: `DUPLICATE_SETTLEMENT.tests` gained `MasumiTermsConflictE2ETest#aSecondTransactionUnderAlreadyClaimedTermsIsADuplicateSettlement`.
- e2e/coverage/wp-17.yml: not created (no error code is owned by this package beyond the wp-3 entry).

CORS binding confirmed two ways on the fresh stack: (1) `docker compose exec facilitator-mempool env` shows `X402_HTTP_CORSALLOWEDORIGINS=https://allowed.e2e.test`; (2) curl preflight `OPTIONS /verify` with that origin on :4023 returns 200 with `Access-Control-Allow-Origin: https://allowed.e2e.test`, `Access-Control-Allow-Methods: POST`; the same preflight on the default instance :4022 returns 403 with no ACAO. The positive CorsE2ETest cases only pass if the binding took effect.

Exact status Spring returns for a rejected preflight: HTTP 403 (no Access-Control-* headers, `Vary: Origin` etc. present) - for a non-allowed origin on the configured instance, and for any origin on the default instance (the default has no CORS mapping, Spring returns 403 for a preflight with Origin + Access-Control-Request-Method).

Production behaviour matched the brief (SettlementService.java:72-73 message and code; CorsConfig.java:26-31). Note: the cited line is 72-73 in this tree (the `fail(` call starts at 72), not a disagreement in behaviour.

Proofs (verbatim tails):
- fresh `e2e/run.sh --keep --tests '*MasumiTermsConflictE2ETest' --tests '*CorsE2ETest'`: 6 tests PASSED (`CorsE2ETest > ... PASSED` x5, `MasumiTermsConflictE2ETest > aSecondTransactionUnderAlreadyClaimedTermsIsADuplicateSettlement() PASSED`), `BUILD SUCCESSFUL in 10s`, `=== e2e: PASSED`.
- full fresh `e2e/run.sh`: exit 0, 173 Java tests PASSED (167 + 6 new; the 174th " PASSED" line is `=== e2e: PASSED`), 0 FAILED/SKIPPED, `MasumiTransferE2ETest` PASSED lines = 26, TS suite `ℹ tests 6 / pass 6 / fail 0`, `=== e2e: PASSED`.
- `./gradlew test`: `BUILD SUCCESSFUL in 45s` (ErrorCodeCoverageTest included).
- Stack torn down (`docker compose -p x402-e2e ... down -v`).
- check-scope.sh vs this package lists 55 files as OUT OF SCOPE: all earlier staged packages' files (it diffs against the base); none of this package's five files are listed.
Assumed: second /verify not asserted (only /settle is specified); terms uniqueness is via random sellerNonce.

### Review and closing changes (orchestrator)
- **Review** (code-reviewer): Spec PASS, Quality PASS.
  - The conflict test reaches the intended branch. The exact message exists only at SettlementService.java:73. The second tx has a different hash, so only the `terms_digest` unique constraint can fail. Equal deterministic Ed25519 `referenceSignature`s prove the terms digests are equal.
  - CORS binding and assertions are sound, and nothing passes vacuously.
- **Minors applied:**
  - `theDefaultInstanceSendsNoAllowOriginHeaderToAnyOrigin` now also covers `/settle`.
  - The two `System.out.println` calls are removed; the 403 status is recorded above.
  - `sharedTerms` is now a copy (`new LinkedHashMap<>(…)`).
  - Skipped: asserting /verify on the second tx. The exact message already pins the branch.
- **Re-run:** `E2E_SKIP_BUILD=1 e2e/run.sh --tests '*CorsE2ETest' --tests '*MasumiTermsConflictE2ETest'` on a fresh stack gave 6 × PASSED, "BUILD SUCCESSFUL in 12s", "=== e2e: PASSED". 0 containers were left afterwards.
- **Scope:** checked against the run's batch scope file, which unions every package's Allowed files. Verbatim output: "in scope: 60 changed file(s) match 58 glob(s)" (exit 0). The builder's own run listed the other packages' staged files, because it used this brief alone.
