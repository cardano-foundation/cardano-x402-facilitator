# WP-2: Every HTTP route has a pinned, schema-checked contract
Tier: R1
Base: 97add9f
Check: `e2e/run.sh --java --tests '*RoutesE2ETest'` green

## Allowed files
- build.gradle
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/kit/**
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/RoutesE2ETest.java
- src/e2eTest/resources/**
- e2e/run.sh (run_java only: explicit-selection branch; editorial addition, needed by this package's own check)

## Rules
- Gradle:
  - Add a new source set `e2eTest` and task `e2eTest` (JUnit 5).
  - Do NOT make `test`, `check` or `bootJar` depend on it.
  - Classpath may include `sourceSets.test.output` (MasumiTestSeller) and main output; only WP-5 builders may import main classes.
  - The task forwards `-PincludeTags`/`-PexcludeTags` to JUnit so run.sh can do the outage pass.
- Env: `FACILITATOR_URL`, `FACILITATOR_MEMPOOL_URL`, `E2E_CHAIN_URL` (Yano direct), `E2E_SHIM_URL`, `E2E_ADMIN_URL`. Missing env fails loudly in stack-dependent tests. Stackless tests (ErrorCodeCoverageTest) must not require it: provide a `@StackRequired` extension that kit tests use, not a global check.
- Kit:
  - `DevnetKit`: fresh random wallet per class; topup via admin; condition-polled UTxO waits; `mintToken` (native-script policy); `assertOnChain(localHash)` against **Yano directly**.
  - `FacilitatorClient`: java.net.http with explicit timeouts; returns status, headers and raw body; one instance per base URL.
  - `ShimClient`: reads the submit counter.
  - `PaymentFactory`: QuickTx; builds a valid signed payment, plus CBOR-level mutation hooks that re-sign where the mutation needs it.
  - `SchemaAssert`: networknt json-schema-validator.
- Schemas (`src/e2eTest/resources/schema/`): VerifyResponse, SettleResponse, SupportedResponse, Health. Written from the x402 v2 spec plus the current code shapes.
- RoutesE2ETest pins:
  - `/supported` exact (default instance minimum 0; mempool instance minimum -1).
  - `/health` ok.
  - Actuator: health, liveness and readiness UP; `/actuator/info` 200.
  - `/actuator/prometheus` contains `jvm_` plus the facilitator's own meter names (grep main for them).
  - `X-Correlation-Id` echo and generation (CorrelationIdFilter).
  - Malformed JSON 400; missing paymentPayload 400; >64 KiB 413; unknown network → 500.
  - Wrong method, wrong content type and unknown path → 500 `internal_error`: pin these and list them as a docs/code gap in the Log.
- Use Lombok where it fits (`@RequiredArgsConstructor`, `@Log4j2`); never use `var`.

## Proof commands
- red-first: n/a; record `red-first unverified: characterization`
- green: e2e/run.sh --java --tests '*RoutesE2ETest'
- regress: JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test (count unchanged)
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-2-java-kit-and-routes.md

## Log
- 2026-10-26 red-first unverified: characterization (pins today's behaviour of a running container).
- 2026-10-26 green: `e2e/run.sh --java --tests '*RoutesE2ETest'` exit 0 in 78 s (cold stack), 27/27. The real verify+settle round trip is confirmed in 4.1 s; the on-chain check goes to Yano directly; the shim submit counter reads exactly 1 (closes WP-1's open shim-counter proof).
- 2026-10-26 regress: `./gradlew test` 365 tests, 0 failures, 0 skipped (src/test untouched).
- 2026-10-26 Out-of-package edit: `e2e/run.sh` run_java runs an explicit Gradle selection once, as given; otherwise the outage-only pass matched nothing and failed with "No tests found".
- 2026-10-26 Prometheus: main registers no meters of its own, so the pin is `jvm_memory_used_bytes` plus Spring's `http_server_requests_seconds_count{…uri="/supported"}`.
- 2026-10-26 Docs/code gaps found (pinned as-is, for the user to decide):
  1. Wrong method / wrong content type / unknown path → 500 `internal_error` (ApiErrorAdvice catch-all swallows Spring's 405/415/404).
  2. A 500 body's `correlationId` is a fresh UUID minted by ApiErrorAdvice, NOT the request's `X-Correlation-Id` (the header and body ids differ). This contradicts CorrelationIdFilter's "every log line and error body … is traceable". Not asserted either way.
  3. A >64 KiB body with a Content-Length gets Spring's default error body (`{timestamp,status,error,path}`); a streamed one gets `{"error":"Request body too large"}`. Two shapes for one condition.
  4. An unregistered network is answered with 500 (by design per the controller comment; the x402 spec would suggest 400).
- 2026-10-26 Local note: Gradle 8.14 does not run on the default Java 25 here. Run with JAVA_HOME=temurin-21 (WP-8 docs).
- 2026-10-26 code-reviewer (1 pass): no route-contract defects; the pins and schemas match src/main. Resolved:
  - topup waits for the exact new UTxO (outRef snapshot + amount); only a 404 reads as empty; utxos() pages through all results.
  - isOnChain doc: not a proof of non-submission; use ShimClient.submitCount == 0.
  - Payment is truly immutable (bytes cloned in/out, deep copies including lists, insertion order kept, nulls allowed); withoutRequirement added.
  - mutateAndResign javadoc states what it does NOT fix (fee, aux-data hash, nonce); a null witness set is guarded; the build() doc covers shared-input nonces.
  - SettleResponse.json has conditional rules (success → confirmed|mempool; confirmed → confirmations ≥ 0; mempool → -1; settlement_pending → pending evidence; evidence → tx hash). New stackless kit/SchemaAssertSelfTest: 7 factory shapes accepted, 12 near misses rejected.
  - Unknown-version pin added: x402Version 1 → 500 "No facilitator registered for scheme: exact and network: cardano:preview" (misleading message, pinned).
  - run.sh explicit args keep `-PexcludeTags=outage` unless they pick tags themselves.
  - ShimClient.fromEnv shares one client.
- 2026-10-26 green after review: `e2e/run.sh --java --tests '*RoutesE2ETest' --tests '*SchemaAssertSelfTest'` exit 0 in 78 s; RoutesE2ETest 29/29, SchemaAssertSelfTest 19/19.
- 2026-10-26 scope: check-scope.sh measures against base 97add9f, so it also lists WP-1's files and the plan docs. WP-2's own changes are inside Allowed files (with the run.sh amendment above).

