# WP-12: Each new transaction shape is one `Case` row, and a new transfer method fails the gate until covered
Tier: R1
Base: 8b481e2
Check: `ShapeMatrixE2ETest` green in `e2e/run.sh`; `./gradlew test` green incl. `TransferMethodCoverageTest`

## Allowed files
- e2e/coverage/wp-4.yml
- e2e/coverage/wp-12.yml
- e2e/coverage/transfer-methods.yml
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/ShapeMatrixE2ETest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/Fixtures.java
- src/test/java/org/cardanofoundation/x402/facilitator/e2e/TransferMethodCoverageTest.java
- docs/work/wp-12-shape-rows.md

## Rules
- **No new format:** shapes are `Case` rows (WP-11) built with `PaymentFactory.Spec.customize` / `preBalance` / `nonceInput`, on CCL 0.7.2. No YAML, no CCL upgrade.
- **Fixtures** (`kit/Fixtures.java`, named and small): `secondUtxo`, `enterpriseWallet`, `mintedToken`, `registeredStake`, `nativePolicy`, `referenceUtxo`.
- **`ShapeMatrixE2ETest`**: one `@ParameterizedTest` over a `Stream<Case>`. It uses `Funding`, asserts via `TransferAssert.assertCase`, and on failure writes the CBOR hex and a short description of the case to `e2e/.out/shapes/<name>/`.
- **Rows and expected results.** "Confirm first" means: read the cited code, and check that the 0.7.2 API can build the shape, before writing the row; if either disagrees, **stop and report**.

  | Row | Built with | Expect | Source |
  |---|---|---|---|
  | multi-input, nonce = second input | `customize(collectFrom(two))` + `nonceInput(1)` | settled | the nonce must be *an* input (ExactCardanoScheme nonce checks) |
  | enterprise-address payer | `enterpriseWallet` | settled | confirm first: `checkPayerAuthorization` |
  | payTo output also carries another token | `customize(payToAddress(payTo, [lovelace, token]))` | settled | confirm first: per-output check, ExactCardanoScheme.java ~280-315 |
  | uppercase payload nonce | `body(nonce → upper)` | settled | nonce lowercased: ExactCardanoScheme.java:42, 100-102, 135 |
  | payTo split into two half outputs | `customize` | `invalid_exact_cardano_payload_amount_insufficient` | ExactCardanoScheme.java:318-321 |
  | script payment with a datum hash | `customize(payToContract(…, datumHash))` | settled | confirm first: script method under the `reference` datum policy, and the 0.7.2 `payToContract` overloads |
  | + stake withdrawal | `customize(withdraw(stake, 0))` + `registeredStake` | `invalid_exact_cardano_payload_phase1_invalid` | key 5 not plain, no `Phase1Validator`: ExactCardanoScheme.java:218-231 |
  | + stake registration certificate | `customize(registerStakeAddress)` | `…_phase1_invalid` | key 4, same |
  | + mint | `customize(mintAssets(nativePolicy, …))` | `…_phase1_invalid` | key 9, same |
  | + reference input | `preBalance(add referenceUtxo)` | `…_phase1_invalid` | key 18, same |
  | + bare native-script witness | `preBalance(add native script to witness set)` | `…_phase1_invalid` | script witness count > 0 (CardanoTransactionDecoder.java:117), same |

- **Rows live in `ShapeMatrixE2ETest` itself**, with expected codes as **string literals**, not `ErrorCodes` constants. The coverage gate checks that the referenced class's own source contains each code's literal value (ErrorCodeCoverageTest.java:135-139).
- **Coverage:** for each error code the rows assert, add `ShapeMatrixE2ETest#<method>` to that code's existing `tests:` list if the code is already mapped. `PHASE1_INVALID`, `AMOUNT_INSUFFICIENT` and `NONCE_INVALID` live in `wp-4.yml` (append only; WP-13 must be finished first, since it also edits `wp-4.yml`). Otherwise map the code in `wp-12.yml`. Log which fragments changed.
- **`TransferMethodCoverageTest`** (in `src/test`, so `./gradlew test` runs it):
  - Reads the method list from `X402FacilitatorRegistry.java` source text (`assetTransferMethods`).
  - Requires `e2e/coverage/transfer-methods.yml` to list, per method, at least one Java e2e positive test (`Class#method`, resolved like the error-code gate) and at least one TS test name present in `e2e/ts/test/*.mjs`.
  - Fails on an unlisted method or a stale entry.
  - Includes self-tests of its own checker (pure functions): a manifest missing one method, a stale entry, and a TS name that doesn't exist are each reported. Without these, red-first only proves the manifest file was missing at base.

## Proof commands
- red-first (gate): `~/.claude/bin/red-first.sh 8b481e2 --baseline './gradlew compileTestJava' src/test/java/org/cardanofoundation/x402/facilitator/e2e/TransferMethodCoverageTest.java -- ./gradlew test --tests '*TransferMethodCoverageTest'`
- `./gradlew test` → green
- `e2e/run.sh --reuse --tests '*ShapeMatrixE2ETest'` → green; full `e2e/run.sh` → green
- scope: per-batch scope file

## Log

### WP-12 implementation (builder)
**Changed**
- `src/test/.../e2e/TransferMethodCoverageTest.java` (new): gate + pure checker + 8 self-tests (parse, full, missing method, stale entry, missing TS name, missing Java test, empty lists, Java resolver).
- `e2e/coverage/transfer-methods.yml` (new): per method (default/masumi/script) positive Java tests and TS test names.
- `src/e2eTest/.../kit/Fixtures.java` (new): secondUtxo, enterpriseWallet, mintedToken, registeredStake, nativePolicy, referenceUtxo.
- `src/e2eTest/.../ShapeMatrixE2ETest.java` (new): `shapes(Case)`, 11 rows; dumps `tx.cbor.hex` + `case.txt` to `e2e/.out/shapes/<name>/` on failure.
- `e2e/coverage/wp-4.yml`: appended `ShapeMatrixE2ETest#shapes` to the existing `tests:` of PHASE1_INVALID and AMOUNT_INSUFFICIENT. No other fragment changed; no wp-12.yml needed (NONCE_INVALID is not asserted by any row).

**red-first (verbatim, first attempt, discarded)**: red for the wrong reason, `symbol: variable ErrorCodeCoverageTest` (WP-9's class does not exist at base 8b481e2). Fixed by making the test self-contained (own `withoutComments`). Final run:
```
red-first: shown red at 8b481e2 ('./gradlew test --tests *TransferMethodCoverageTest'):
    java.lang.AssertionError at TransferMethodCoverageTest.java:53

9 tests completed, 1 failed
```
Line 53 is `assertThat(MANIFEST).exists()`: the gate test is red because the manifest is missing, the 8 self-tests are GREEN at base (the checker lives in the same file, so it cannot be red at base). Substitute proof that the self-tests bite: with the checker mutated (stale loop, TS-name check, missing-method report disabled) `./gradlew test --tests '*TransferMethodCoverageTest'` gave `aStaleManifestEntryIsReported() FAILED`, `aMethodMissingFromTheManifestIsReported() FAILED`, `aTsNameThatDoesNotExistIsReported() FAILED`, `9 tests completed, 3 failed`; restored, green.

**Per-row outcome (all 11 PASSED in the fresh run)**
| Row | Result |
|---|---|
| multi-input, nonce = 2nd input | settled (collectFrom both UTxOs, nonceInput(1), asserts nonce == inputs[1]) |
| enterprise payer | settled |
| payTo output carries lovelace + token | settled |
| uppercase nonce (Payment edit per ruling) | settled, the facilitator normalises the nonce |
| script + datum hash | settled |
| split into two halves | amount_insufficient (message "pays 1000000, requires 2000000") |
| withdrawal / registration / mint / reference input / native-script witness | phase1_invalid each |

**"Confirm first" rows: what I checked**
- enterprise payer: `ExactCardanoScheme.checkPayerAuthorization` (l.359-): Key credential in `verifiedWitnessKeyHashes` is accepted, enterprise has a key payment credential; confirmed by the row settling. The wallet is an `Account` subclass overriding `baseAddress()` to return `enterpriseAddress()` (CCL 0.7.2 `Account` is non-final), so `Wallet.address()` is the enterprise address and `payer` matches in `assertVerifiedAndSettled`.
- token on the payTo output: `checkValueTransfer` (l.~290-320) picks the first payTo output covering the amount, then min-UTXO via `MinAdaCalculator` on the whole output; passes with a token present.
- script + datum hash: `ScriptTransferVerifier.check`, policy `reference`: only V1+inline datum is refused, a hash is fine. 0.7.2 `AbstractTx.payToContract(String, Amount, String)` exists; the test asserts the decoded output carries that datum hash and no inline datum.

**Deviations (please review)**
1. QuickTx 0.7.2 MERGES two `payToAddress` calls to the same address into one output (the first split attempt via `customize(payToAddress)` verified VALID, tx `67d2ff59...`, output 2 ADA). The split row is therefore built with `preBalance` (a `TxBuilder` that replaces the output to payTo with two halves), still the brief's expected code, with an assertion that the built tx has two outputs to payTo.
2. The builder's `Spec` always adds its own output to `payTo`. For the token row and the script row that output would be the one the facilitator checks first, defeating the shape. So the real payTo is attached with `withRequirement("payTo", ...)`: the Spec pays a decoy (token row) or the receiver (script row), and the shape output (token / datum-hash lock) is the one named in the requirements. Row asserts check the intended output carries token / datum hash.
3. Settled rows use `Case.base(shape)` (control = the shape itself), so for them step 1 verifies the same payment that step 3 settles. The rejected rows use the standard base as control and build the shape in `defect`.
4. `TransferMethodCoverageTest` carries its own `withoutComments` (copy of ErrorCodeCoverageTest's) so it compiles at base; TS names are matched as `test("<name>"`.
5. Registration of the stake key for the withdrawal row works on the devnet (`Fixtures.registeredStake`, submitted and awaited).

**Proof (verbatim tails)**
- `./gradlew test`: `> Task :test` / `BUILD SUCCESSFUL in 42s` (re-run after the final edits).
- `e2e/run.sh --reuse --tests '*ShapeMatrixE2ETest'`: 11 lines `... PASSED`, `BUILD SUCCESSFUL in 34s`, `=== e2e: PASSED`.
- Full fresh `E2E_SKIP_BUILD=1 e2e/run.sh` (after tearing down my earlier `--keep` stack): `BUILD SUCCESSFUL in 4m 9s`, `=== e2e: PASSED`, `exit 0`; 167 Java `... PASSED` lines (156 + 11 new); TS suite also passed. Stack torn down by run.sh (`docker ps` empty).
- check-scope: reports 50 out-of-scope paths, all the earlier staged packages' files (the index baseline); none of the WP-12 files appear in the list.

### Fix round 1
1. **Dump keeps the CBOR for every failure.** `shapes` keeps `Payment last` (set to the control right after the base builds, then to `defective`) and dumps `last`; `dump` now does `failure.addSuppressed(e)` on IOException instead of throwing. Proof: ShapeMatrix run below green (no failing row to dump; change verified by inspection).
2. **Real TS resolver is tested.** New `theTsResolverFindsOnlyNamesDeclaredAsTests`: present name true; absent, prefix-only ("default method"), string-not-a-test, empty and "test(" all false; a commented-out `test("commented out"` is also false, which needed `tsTestExists` to strip comments first (stricter, no check weakened). Mutation (resolver made `return true || ...`), verbatim:
```
> Task :test FAILED
TransferMethodCoverageTest > theTsResolverFindsOnlyNamesDeclaredAsTests() FAILED
10 tests completed, 1 failed
```
   Restored: `./gradlew test` BUILD SUCCESSFUL, TransferMethodCoverageTest `tests="10"`.
3. **Mint row isolates key 9.** `mintWithoutScriptWitness`: customize(mintAssets) + `preBalance` that empties the witness set's native scripts. The builder asserts the decoded tx has a non-empty mint (key 9) and no native-script witnesses, so the row fails loudly if CCL ever re-adds the script. Row still expects `invalid_exact_cardano_payload_phase1_invalid` and passes: `... phase1_invalid <- plus a mint PASSED`.
4. **`Fixtures.registeredStake` waits** for the registration tx's change output to be visible at the wallet (Poll.until, 120 s), like Funding.refill. Withdrawal row PASSED.

Proof: `E2E_SKIP_BUILD=1 e2e/run.sh --tests '*ShapeMatrixE2ETest'` (fresh): 11 x `PASSED`, `BUILD SUCCESSFUL in 38s`, `=== e2e: PASSED`; `docker ps` empty afterwards. `./gradlew test` BUILD SUCCESSFUL.

### Re-review and scope (orchestrator)
- Scoped re-review of fix round 1: items 1–4 ADDRESSED, nothing weakened, "WP-12 re-review: CLEAN". The re-review checked the TS comment stripping against the real `sdk.test.mjs`: the one `//` inside a string, a URL on line 32, holds no test name.
- Parked as optional:
  - (A) When a defect builder's own assertion fails, the dump holds the control's CBOR.
  - (B) A RuntimeException thrown inside `dump` would replace the original failure.
- Scope (the review's I4): the builders ran `check-scope.sh` against this brief alone, so it listed the earlier packages' staged files. The batch check against the run's scope file, which unions every package's Allowed files, prints verbatim "in scope: 56 changed file(s) match 52 glob(s)" (exit 0).
