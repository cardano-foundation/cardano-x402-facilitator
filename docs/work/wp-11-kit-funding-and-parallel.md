# WP-11: Every devnet case is a shared `Case` row, tests fund from one fan-out, and classes run in parallel — with identical assertions
Tier: R1
Base: 8b481e2
Check: `e2e/run.sh` green with the same Java test names and count as after WP-9 (135 in `e2eTest`); `e2eTest` wall time recorded before and after

## Allowed files
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/Case.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/Funding.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/TransferAssert.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/MasumiLock.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/PaymentFactory.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/DevnetKit.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/DefaultTransferE2ETest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/ScriptTransferE2ETest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/MasumiTransferE2ETest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/RejectionMatrixE2ETest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/SettlementSemanticsE2ETest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/RoutesE2ETest.java
- src/e2eTest/resources/junit-platform.properties
- src/e2eTest/resources/META-INF/services/org.junit.jupiter.api.extension.Extension
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/IsolatedStackGuard.java
- docs/work/wp-11-kit-funding-and-parallel.md

## Rules
- **Refactor only for existing tests.** Every existing test keeps its name, its checks and its assertion messages. Moving an assertion into a helper is allowed only if the helper performs exactly the same checks. The reviewer diffs every touched test for this.
- **`kit/Case.java`, the one case format** for every devnet transaction case (rejection matrix, Masumi rejections, later shapes and the load mix):
  - Fields:
    - `name`;
    - `base`: `Function<Wallet, Payment>`, default `standardBase`;
    - `defect`: optional `BiFunction<Payment, Wallet, Payment>` for transaction mutations;
    - `bodyDefect`: optional `UnaryOperator<Map<String,Object>>` for envelope edits;
    - `expectedMessage`: optional;
    - `expect`: `SETTLED` or an error-code value.
  - A `toString` of `expect ← name` keeps today's display names.
  - Builders: `expect(...)`, `defect(...)`, `body(...)`.
  - `RejectionMatrixE2ETest`'s `MESSAGES` map and `bodyOf` switch become `Case` fields, so renaming a case can't silently drop them.
  - Only the `Case` **type** goes in `kit/`. The rows stay in their test classes, with expected codes as string literals: the coverage gate checks that the referenced class's own source contains each code's value (ErrorCodeCoverageTest.java:135-139).
- **Masumi:** the inline `Lock` builder moves to `kit/MasumiLock.java` unchanged. The 23 Masumi rejection rows become `Case` rows with identical checks; the 26 Masumi tests keep their names.
- **Funding** (`kit/Funding.java`): a process-wide pool. `Funding.wallet(long ada)` returns a fresh wallet holding exactly one UTxO of `ada`. The pool refills under a lock: one faucet top-up to a bank wallet, then one fan-out transaction (chunked if needed; measure the faucet and tx-size limits and log them) to N fresh accounts, then one wait for inclusion. `DevnetKit.newWallet` stays.
- **TransferAssert** (`kit/TransferAssert.java`): the duplicated `assertVerifiedAndSettled` (Default/Script/Masumi), `assertRejected` / `rejects` (Script vs the matrices) and the `decode` / `unchecked` helpers, moved without behavioural change. It also gains `assertCase(Case, Payment)`, which applies `expect` the same way the matrices do today.
- **PaymentFactory.Spec:**
  - `customize(Consumer<Tx>)`, applied to the QuickTx `Tx` before build.
  - `preBalance(TxBuilder)`, passed to `QuickTxBuilder.TxContext.preBalanceTx`, for body fields 0.7.2's `Tx` can't express (reference inputs, a bare native-script witness).
  - `nonceInput(int index)`: picks the payload nonce from the built transaction's inputs (default 0 = today).
  - Fix the orphan Javadoc above `inlineDatum`, which documents `metadata`.
- **Shared base payment:** the rejection cases that only edit the envelope, requirements, decoding or recipient (they never spend) share one base payment built in `@BeforeAll`; the others take a wallet from `Funding`. Log the list of shared-payment cases.
- **Parallel classes:** `junit-platform.properties` enables parallel execution with `mode.default = same_thread` and `mode.classes.default = concurrent`. Any class sharing mutable state (receivers, the shim's global counters, instance-wide settings) gets `@ResourceLock` or `@Isolated`. If any flake appears in 3 consecutive full runs, fall back to `mode.classes.default = same_thread`, keep the rest, and log it.
- **`IsolatedStackGuard`:** an auto-detected extension (`junit.jupiter.extensions.autodetection.enabled = true` plus the ServiceLoader file) whose `AfterAllCallback` runs after every `@Isolated` class. It checks `@Isolated` on `context.getRequiredTestClass()`, because an auto-detected extension runs for every class. JUnit runs `AfterAll` callbacks while the class still holds its isolation lock, so the wait completes before any other class starts. It waits until **both** facilitators report readiness UP, so the next classes never start against a half-recovered stack. This covers `ChainOutageE2ETest`, which today waits only for the default instance (`ChainOutageE2ETest.java:127-130`), without editing it. WP-13 extends it to clear shim faults and the profile.

## Proof commands
- red-first: no new tests (refactor + infrastructure)
- Before any change: `e2e/run.sh`; record the `e2eTest` wall time and test count
- After: `e2e/run.sh` three times in a row (`--reuse` after the first) → all green, same names and count; record wall times
- `./gradlew test` green
- scope: per-batch scope file

## Log

### Implementer log (WP-11)

**Baseline** ("baseline with additive kit edits"): `e2e/run.sh --keep` before any behavioural change. Source state it compiled: base tree + the two NEW, unused files `kit/Case.java` and `kit/MasumiLock.java` (already written when Gradle compiled `e2eTest`). The DevnetKit/PaymentFactory edits came after that compile finished, and all other edits were staged outside the tree until it did. Result: Java 136/136, `e2eTest` BUILD 6m25s (run.sh total 11m10s, including image build, DevKit boot, TS suite 6/6). Clean WP-9 reference: 136/136 in 8m18s (image without WP-16).

**After**: three consecutive green `e2e/run.sh --reuse` runs (stack booted by the baseline run, WP-16 image): Java 136/136 each, `e2eTest` BUILD 2m22s / 2m27s / 2m26s (run.sh total 2:56 / 3:02 / 3:00 including TS 6/6). Test-name diff against the baseline JUnit XML: identical (136 names, `diff` empty). `./gradlew test`: BUILD SUCCESSFUL, 376 tests, 0 failures (ErrorCodeCoverageTest 7/7). Count: 136 e2eTest + 7 = 143.

**Files**
- kit/Case.java (new): the one case format; builders `expect(code,name)`, `base`, `defect`, `body`, plus `rawJson`, `message`, `sharedBase`, `arrow`.
- kit/MasumiLock.java (new): the inline Lock builder moved over, fields public, `Deployment`/`Built` records, `terms/commitment/part/recommit/identifier/outputTo` helpers.
- kit/Funding.java (new): process-wide pool, `Funding.wallet(long ada)`.
- kit/TransferAssert.java (new): `assertValid`, `assertVerifiedAndSettled` (+ overload with extra settle checks), `assertRejected` (+ explicit request/message overload), `assertControlValid`, `assertCase(Case, Payment)`, `requestOf`, `decode`, `unchecked`.
- kit/IsolatedStackGuard.java, META-INF/services/org.junit.jupiter.api.extension.Extension, junit-platform.properties (all new).
- kit/PaymentFactory.java: `Spec.customize`, `preBalance`, `nonceInput`; orphan Javadoc fixed.
- kit/DevnetKit.java: faucet top-ups serialised by a process-wide lock (parallel classes plus Funding would otherwise race on faucet UTxOs). `newWallet` unchanged.
- Test classes: Default/Script/Masumi/RejectionMatrix/SettlementSemantics/Routes use `Funding.wallet`; Default/Script/Masumi/Matrix use TransferAssert; Matrix and Masumi rows are `Case`s.

**Measured limits**: the faucet (`/local-cluster/api/addresses/topup`) accepted 1e9 ADA in one call; no limit found. `max_tx_size` = 16384 B; a fan-out output is about 65 B, so about 240 fit in theory; chunk size set to 100 outputs per tx. Refill = 30 wallets per amount (one tx), so the chunk loop is NOT exercised by the suite and is untested code.

**Shared-payment cases** (RejectionMatrixE2ETest, 19 of 42; one base built in @BeforeAll with ttl 550 slots so it outlives the class): accepted.scheme differs; unknown assetTransferMethod; accepted.network differs; empty nonce; empty transaction; malformed nonce; nonce is not an input; amount 0; amount with leading zero; maxTimeoutSeconds 0; non-canonical asset; confirmationPolicy above 20; confirmationPolicy with an unknown member; payTo on mainnet; transaction is not base64; transaction is not a CBOR tx; payTo is someone else; asset is a token the tx does not carry; amount above what is paid. The other 23 take a `Funding.wallet(20)`.

**Parallel**: `mode.classes.default = concurrent`, methods same_thread, autodetection on. `@Isolated`: only ChainOutageE2ETest (already was; it pauses the shim); IsolatedStackGuard waits for both facilitators READY after it. No class got `@ResourceLock`: shim counters are per transaction hash, receivers are per class, Masumi's MINTED map is concurrent, faucet access is serialised in DevnetKit. 3 green runs, no flake, so **no fallback applied**.

**Deviations from the brief text** (all to keep names/checks identical):
- `Case.toString` is `expect ← name`, but Masumi's existing display names use ASCII `<-`; added `Case.arrow(String)`, Masumi rows set `<-`, so names are unchanged.
- Masumi's lone-surrogate row edits the serialized JSON, which `body(UnaryOperator<Map>)` cannot express: added `Case.rawJson`.
- Default's two extra settle checks (network, confirmations) now run after the status check instead of before it (order only; same check set).
- Pool pre-fills 30 wallets per distinct amount (5, 10, 20, 40, 100; 30 only via the Masumi ada test), so some devnet ADA is wasted.

### Fix round 1

- I1: `RejectionMatrixE2ETest.sharedBase()` is a synchronized accessor; it rebuilds the base when `kit.tipSlot() > startSlot + 550 - 60` (start slot taken before the build, so conservative). Used by `shared(...)` rows and `@BeforeAll`. Proof: fresh and reuse runs green, 136/136.
- I2: `TransferAssert` holds one lazily initialised `FacilitatorClient` and one `DevnetKit` (holder class `Shared`); no per-call clients.
- M3: `assertCase` throws `IllegalArgumentException` for a SETTLED case carrying `bodyDefect`, `rawJsonEdit` or `expectedMessage`.
- M4: `Case.base()` defaults to `Case::standardBase` (2 ADA lovelace to `Case.standardReceiver()`, built as the matrix did); the matrix uses it, its own `standardBase` removed, its `receiver` is `Case.standardReceiver()` (one Funding wallet of 5 ADA per process, so the matrix no longer funds its own).
- M5: `MasumiLock.deployments()` (record `Deployments(allowlisted, notAllowlisted)`) now loads the file; `MasumiTransferE2ETest` only calls it. Same file/env logic.
- M7: the parallel green runs (WP-11 and this round) used the WP-16 facilitator image, where the chain-lookup pool queues a call for its remaining budget; WP-11's parallelism depends on WP-16 (without it, concurrent classes could see instant `chain_lookup_failed`).
- M8: duplicate "transaction surgery" comment removed.

Proof: fresh `e2e/run.sh --keep`: Java 136/136, BUILD 2m27s (run.sh total 4:01 incl. image build, boot, TS 6/6), e2e: PASSED. `e2e/run.sh --reuse`: Java 136/136, BUILD 2m26s (total 3:00), e2e: PASSED. Test-name diff against baseline XML: empty (136). `./gradlew test`: BUILD SUCCESSFUL. Stack torn down (0 x402-e2e containers).
Unverified: the I1 rebuild path (needs a class longer than ~490 s) and the M3 exception were not exercised by any run.

### Re-review and closing change (orchestrator)
- Scoped re-review of fix round 1: I1, I2, M3, M4, M5, M7 and M8 are addressed. The shared receiver is safe: only the matrix uses it, and no check reads its balance. "WP-11 re-review: CLEAN".
- Its optional point is applied: `Case.standardBase` used to create a `DevnetKit` per call, and now it uses a private `SharedKit` holder, like `TransferAssert.Shared`. Proof: `compileE2eTestJava` exits 0, and `e2e/run.sh --tests '*RejectionMatrixE2ETest'` on a fresh stack gives "BUILD SUCCESSFUL in 22s" with 39/39 JUnit cases and 0 failures.
- Rulings: M1 (the fan-out chunk loop is never exercised and the tx-size limit is estimated) moves to WP-18, which needs bulk wallets. M2 (one global Funding lock) and M6 stay as they are.
