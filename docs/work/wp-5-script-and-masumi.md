# WP-5: Script and Masumi transfer methods are proven on-chain, including their rejection codes
Tier: R1
Base: 97add9f
Check: `e2e/run.sh --java --tests '*ScriptTransferE2ETest' --tests '*MasumiTransferE2ETest'` green

## Allowed files
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/ScriptTransferE2ETest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/MasumiTransferE2ETest.java
- src/e2eTest/resources/scripts/**
- e2e/coverage/wp-5.yml

## Rules
- Script:
  - Pay to always-succeeds V2 and V3 script addresses, using `extra.script` with and without parameters, and `extra.scriptHash`.
  - Negative cases: SCRIPT_ADDRESS_MISMATCH, SCRIPT_DATUM_MISSING (V1 inline datum under the reference policy).
  - No script spending.
- Masumi:
  - Use the fixed deployment from `e2e/devnet/masumi-deployment.json` (owned by WP-1, read-only here) as `extra.deployment`.
  - Add a consistency test: the committed script hash equals the hash main's MasumiBlueprint derives from those params.
  - Positive: ADA and token locks, settled and found on-chain; assert the inline datum on the escrow UTxO via Yano direct.
  - Negative, each with a valid-base control: masumi schema, commitment, deployment, seller_signature, contract_mismatch (a second, non-allowlisted deployment), agent_identifier, identifier, escrow_output_count, datum_missing, reference_script, datum_invalid, deadline, datum_mismatch, collateral, asset, min_utxo, and VERIFICATION_ERROR (malformed manifest string, MasumiTransferVerifier.java:122-125).
- Oracle: you may use main-code MasumiDigests/MasumiDatum/MasumiBlueprint and the test MasumiTestSeller to BUILD vectors. WP-7 (TS SDK) cross-checks positive correctness. Say this in the class Javadoc.

## Proof commands
- red-first: n/a; record `red-first unverified: characterization`
- green: as Check
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-5-script-and-masumi.md

## Log
- Files: src/e2eTest/.../ScriptTransferE2ETest.java (6 tests: V3, V2+inline datum, V3+params, scriptHash-only positives; script_address_mismatch, script_datum_missing(V1+inline) negatives); MasumiTransferE2ETest.java (blueprint consistency, ADA lock, token lock with Yano datum readback, 24 parameterized control+defect cases); e2e/coverage/wp-5.yml (19 codes, all `tests:`, no exclusions). No src/e2eTest/resources/scripts needed.
- red-first unverified: characterization
- Green: `E2E_COMPOSE_PROJECT=x402-e2e-wp5 ... e2e/run.sh --java --tests '*ScriptTransferE2ETest' --tests '*MasumiTransferE2ETest'` cold -> exit 0, "=== e2e java: PASSED", 33 PASSED lines (6 script + 3 + 24 masumi), 0 FAILED. `./gradlew e2eTest --tests '*ErrorCodeCoverageTest'` 7/7 PASSED.
- Run against the corrected 28-byte-vkey masumi-deployment.json (hashes read from file, never hardcoded).
- Strictness finding: the first run used the old 29-byte vkeys; ALL lock/rejection cases failed there, i.e. the Java facilitator REJECTS a non-28-byte admin vkey in extra.deployment (MasumiSchema requires exactly 56 hex chars -> masumi_schema). Not lenient.
- All defect triggers produced the intended code first time; no relabelling; VERIFICATION_ERROR produced by a lone surrogate (\ud800) in a commitment part name (no content), sent via raw JSON.
- Assumed: payByTime etc. use wall clock (devnet startTime ~ real time). Test code uses TestTx (test-source) as datum builder in addition to MasumiTestSeller.
- check-scope: my files (Script/MasumiTransferE2ETest, wp-5.yml) are in scope; other listed paths belong to earlier packages.
- 2026-10-27 code-reviewer: fixed 'positive collateral below the minimum' — it now sends amount+collateral, so only the minimum rule is broken (before, the output-sum check fired too and would have masked a regression). The agent-identifier case was renamed: the container has no registry validator, so any non-empty agent is refused.
