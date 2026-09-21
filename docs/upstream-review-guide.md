# Review the upstream compatibility upgrade

The implementation aligns the Java facilitator with `@x402/cardano` / core
2.26.0 and adds durable settlement guarantees. I would merge the implementation:
automated gates pass and independent correctness review has no remaining material
findings; live-chain validation remains a separate deployment check.

## Read these first

1. [SettlementService](../src/main/java/org/cardanofoundation/x402/facilitator/service/settlement/SettlementService.java),
   especially `resume` and `observe`, alongside the claim/policy fences in
   [SettlementRepository](../src/main/java/org/cardanofoundation/x402/facilitator/repository/SettlementRepository.java)
   and the [V2 migration](../src/main/resources/db/migration/V2__upstream_main_settlement.sql).
   Can any uncertain or legacy payment be re-broadcast, promoted on invented
   evidence, or returned as successful below its persisted confirmation depth?
2. [ExactCardanoScheme](../src/main/java/org/cardanofoundation/x402/facilitator/service/verification/ExactCardanoScheme.java),
   especially `verifyBroadcast` and `checkPhase1`, with the decoder and Blockfrost
   evidence changes. Are pre-broadcast checks bypassed only after established
   provenance or independently authenticated ledger acceptance? Are missing
   values, invalid signatures, imbalance and unsupported ledger operations
   rejected before fresh submission?
   Also inspect `BlockfrostChainService.getCurrentSlot` and `ShelleyNetworkClock`:
   validity now follows the SDK's wall-clock slot, including floor rounding,
   while provider health and chain evidence still query Blockfrost. A latest
   block thirty slots behind must not reject a valid `now + 600 seconds` expiry.
3. [MasumiTransferVerifier](../src/main/java/org/cardanofoundation/x402/facilitator/service/verification/method/masumi/MasumiTransferVerifier.java),
   with schema, COSE and commitment helpers. Does the authenticated seller quote
   bind the exact lock and all identifier fields? Can either an unapproved
   deployment or an unverified registry claim pass?

## Skim

- Registry, response DTO and configuration changes: one capability range,
  75-second wait, actual confirmation counts and optional validator beans.
- PostgreSQL upgrade/concurrency tests: all V1 states, single broadcast,
  policy strengthening, terms conflicts and stale-worker fencing.
- `interop/test.mjs` and its test-only Java server: actual published HTTP client
  and resource-server behavior against controlled chain evidence.
- [Upgrade notes](upstream-compatibility.md): rollout constraints, extension
  points and the remaining live-chain verification limit.
- Dockerfile and Compose: all facilitator images select `linux/amd64` to match
  the bundled Aiken native library. Builds exercise Masumi and script derivation;
  `.dockerignore` excludes local overrides and keys from the build context.

## Safe to skip line by line

Generated signed-payment JSON and the Node lockfile. The generator pins upstream
packages, verifies eight valid and ten invalid vectors independently, and the
HTTP gate checks Java against them. Inspect the generator and assertions instead.

## Verification evidence

The delegated test-runner ran a clean build and then a full rerun after the
upstream rejection vectors exposed a canonical-amount/asset error-code mismatch.
The implementation now returns `invalid_exact_cardano_requirements` for those
cases, matching TypeScript while retaining the rejection assertions.

| Gate | Result |
|---|---|
| Java 21 compile and `bootJar` | Passed |
| Unit/integration tests | 313 passed, 0 failures/errors/skips |
| PostgreSQL, included above | 31 passed, including all V1 statuses and concurrency |
| Published TypeScript HTTP interoperability | 30 checks passed |
| JAR contents | No test, interop or Node dependencies |
| Diff hygiene | `git diff --check` passed |
| Linux x86-64 container | Masumi/script build tests and final JRE Masumi derivation passed |
| Architecture/lint/format tasks | No separate project gate configured |
| Live preprod or mainnet transactions | Not run |

Final command: `./gradlew test bootJar interop` with Java 21 and installed pinned
interop dependencies, following the initial `clean test bootJar interop` run.
The same gate passed again after the demo TTL fix and clock-boundary regressions.

## Correctness review

The independent reviewer examined verification, Masumi authorization, provider
evidence, claim ownership, legacy migration and HTTP interop. It found one
material issue: unpaired UTF-16 surrogates could produce Java-only commitment
digests. The fix rejects them before UTF-8 encoding, preserves valid pairs, and
matches upstream error classification. The reviewer rechecked the fix against
38 dedicated Unicode cases; the full gate then passed again. No blocking or
should-fix findings remain.

A follow-up demo regression exposed a clock mismatch: Java previously treated
the latest block slot as the current slot, rejecting legitimate SDK expiries
between blocks. The fix uses the configured network clock and logs the slots
and quoted timeout on TTL-limit rejection. Review also caught pre-anchor
rounding; `Math.floorDiv` now matches TypeScript, and nonpositive slot lengths
fail at construction. The reviewer rechecked both fixes with no remaining
material findings.

The remaining deployment questions are live provider behavior and supplying a
complete validator for advanced ledger transactions. Ordinary transfers are
covered by the built-in validator; unsupported advanced bodies fail closed.

## Working-tree state

All changes are uncommitted on `feat/upstream-main-compatibility`. Nothing has
been pushed or deployed.
