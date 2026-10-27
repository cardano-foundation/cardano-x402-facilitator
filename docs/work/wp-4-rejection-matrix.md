# WP-4: Every devnet-reachable error code is asserted, and code coverage is gated
Tier: R1
Base: 97add9f
Check: `e2e/run.sh --java --tests '*RejectionMatrixE2ETest' --tests '*ErrorCodeCoverageTest'` green, and the sabotage run fails on exactly the sabotaged case

## Allowed files
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/RejectionMatrixE2ETest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/ErrorCodeCoverageTest.java
- e2e/coverage/wp-4.yml

## Rules
- One parameterized case per (constant, trigger). Each case:
  1. Builds a REAL funded valid payment and asserts the **unmutated** base is `isValid:true` (control).
  2. Applies exactly one mutation and asserts HTTP 200, `isValid` false, the exact `invalidReason`, and a schema-valid body.
  3. Sends the same mutated payload to `/settle` and asserts success false, the same reason, and `transaction` "".
- Minimum set (default method):
  - Envelope and nonce: UNSUPPORTED_SCHEME, NETWORK_MISMATCH, NONCE_INVALID (malformed; an empty nonce gives INVALID_PAYLOAD, ExactCardanoScheme.java:96-97), NONCE_NOT_IN_INPUTS, NONCE_NOT_ON_CHAIN (spent nonce).
  - Inputs: INPUT_NOT_AVAILABLE (a second payer input that is already spent).
  - Decoding: TRANSACTION_DECODE_FAILED (bad base64, bad CBOR).
  - Signatures: UNSIGNED, INVALID_SIGNATURE, PAYER_NOT_WITNESS.
  - Network: NETWORK_ID_MISMATCH (tx network_id, and an output tagged for mainnet).
  - Phase 1: PHASE1_INVALID (duplicate inputs, unwitnessed required signer, script-credential input). No "bad fee" trigger: CBOR uint plus conservation make it unreachable as PHASE1_INVALID.
  - Phase 2: PHASE2_INVALID (is_valid=false).
  - Validity interval: TTL_EXPIRED, TTL_TOO_FAR, NOT_YET_VALID.
  - Value and fees: VALUE_NOT_CONSERVED, FEE_BELOW_MINIMUM, MIN_UTXO_INSUFFICIENT.
  - Recipient and amount: RECIPIENT_MISMATCH, ASSET_MISMATCH, AMOUNT_INSUFFICIENT.
  - Requirements: amount, maxTimeout, asset, policy (malformed confirmationPolicy).
  - Generic INVALID_PAYLOAD: empty nonce, missing TTL, payTo on the wrong network, and tx above max size. The oversize case must be a decodable signed tx: one between 16384 and 32768 bytes hits `:160`, and one above 32768 hits `:104`. Settle decodes before checking size (SettlementService.java:50-53), so an undecodable blob would return DECODE_FAILED on settle. Read `max_tx_size` from live protocol params rather than hardcoding it (the spike saw 16384). Keep the >32768-byte tx under ~45 KB so its base64 stays below the 64 KiB request cap and doesn't hit 413.
- If a trigger yields a different code than intended, investigate the check ordering in ExactCardanoScheme. Don't relabel the case.
- `ErrorCodeCoverageTest`:
  - Stackless: no `@StackRequired`.
  - Merges `e2e/coverage/wp-*.yml`. Each entry is keyed by **constant name** and holds either `tests: [ClassName#method]` or `excluded: <race-only|dead|provider-fault|unreachable-via-http|not-producible-on-healthy-devnet>` plus a reason.
  - Parses ErrorCodes.java source text (no class import) and fails on any unmapped constant, stale entry, or duplicate across fragments.
  - For each referenced test, checks that the test's source file contains the constant's string value.
- This package's fragment also carries the exclusions in the plan's Skipped list.
- Sabotage proof: in a throwaway worktree, comment out one check (e.g. FEE_BELOW_MINIMUM), build the image, run the matrix, and paste the red output into the Log. Discard it; never commit it.

## Proof commands
- red-first: the sabotage run replaces it (paste its output verbatim)
- green: as Check
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-4-rejection-matrix.md

## Log
- 2026-10-26 green: RejectionMatrixE2ETest 39/39 against the live stack (one setup fix: QuickTx needs from() even with collectFrom; the spend now asserts it leaves the nonce untouched).
- 2026-10-26 ErrorCodeCoverageTest: all checks green except everyConstantIsMapped, which lists exactly the Masumi/script constants + VERIFICATION_ERROR (WP-5) and CHAIN_LOOKUP_FAILED/SETTLEMENT_PENDING (since mapped in wp-6.yml). VERIFICATION_ERROR moved out of wp-4.yml: WP-5 produces it via a malformed Masumi manifest.
- 2026-10-26 Sabotage run 1 stopped at the user's pause before reaching the fee case (inconclusive).
- 2026-10-27 Sabotage proof (replaces red-first). Throwaway worktree at a6f9fab, one line added before the fee check in ExactCardanoScheme.checkPhase1: `if (false) // SABOTAGE`; image x402-facilitator:sabotage; `E2E_IMAGE=x402-facilitator:sabotage e2e/run.sh --java --tests '*RejectionMatrixE2ETest'` → exit 1, `39 tests completed, 1 failed`. The only failure, verbatim:
  ```
  RejectionMatrixE2ETest > rejects(Case) > invalid_exact_cardano_payload_fee_below_minimum ← fee below the minimum FAILED
      org.opentest4j.AssertionFailedError: [HTTP 200 {"isValid":true,"payer":"addr_test1qrf9gl5q4s3gcjxg32yyxls8gwnr4ywnatcxzfuv2yf403keyfck34slpa77eg439mcxdz9agvlemxlrlz9vg7zvjwpqh3t0yr"}]
      Expecting value to be false but was true
  ```
  All 38 other cases passed. The failure fires at the verify assertion, before /settle, so the sabotaged facilitator never broadcast the low-fee tx. Worktree and image deleted; nothing committed.
- 2026-10-27 code-reviewer (1 pass over WP-4..8): no correctness defects. Fixed: oversize cases now pin the message, since only it tells the 32 KiB-cap check from the protocol-size check; coverage gate ignores code strings in comments and rejects duplicate keys within a fragment.
- 2026-10-27 green after review: part of the final full cold run (see wp-8 Log).
