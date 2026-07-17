# Cardano x402 Facilitator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> **This run:** executed inline by the orchestrating session (owner directive: autonomous, no questions), with **Codex (GPT-5.6 Sol) verification gates at each phase boundary** instead of per-task subagent reviews. **Commit steps are deliberately omitted** — the repo owner's standing instruction is "commit only when I ask".

**Goal:** Implement the spec at `docs/superpowers/specs/2026-07-16-cardano-x402-facilitator-design.md` — a production Cardano x402 facilitator (Spring Boot, Blockfrost/yaci-store backends, Postgres) — proven by a Java main class that executes the full x402 flow on **preprod** (build+sign with cardano-client-lib → `/verify` → `/settle` → independent on-chain confirmation).

**Architecture:** Single Spring Boot Gradle project, root package `org.cardanofoundation.x402.facilitator`, layered `model`/`service`/`controller`/`chain`/`repository`/`config` per spec §4.1. Verification pipeline per §6, settlement state machine per §7/§8, chain SPI per §9.1.

**Tech Stack:** Java 21 (Temurin, via Gradle toolchain; wrapper 8.14 copied from the demo — the proven combo), Spring Boot **3.5.16**, Log4j2, `com.bloxbean.cardano:cardano-client-lib:0.7.2` + `cardano-client-backend-blockfrost:0.7.2`, yaci-store **2.0.2** starters (P6), PostgreSQL 17 (Flyway), JUnit 5.

## Global Constraints (from spec — every task inherits these)

- Root package `org.cardanofoundation.x402.facilitator`; group `org.cardanofoundation`; app class at package root (§4.1).
- Error-code strings **verbatim** from the TS catalogue (§6; `x402/typescript/packages/mechanisms/cardano/src/constants.ts`) — cross-SDK wire compatibility.
- JSON: records, Jackson, `NON_NULL`, unknown-fields-lenient (§5).
- Networks `cardano:mainnet|preprod|preprod|preview` canonical + CIP-34 aliases normalized, exact-match (no case folding) (§6 A3, §10).
- Fail-closed: chain-lookup uncertainty ⇒ reject, never approve (§3).
- Logging via Log4j2 (`spring-boot-starter-log4j2`, Logback excluded) (§13).
- Settlement claim key = ledger tx id (blake2b-256 of body bytes); fenced CAS transitions on `(tx_hash, attempt_id, expected_status)` (§8).
- Porting sources (read them, don't re-derive): demo `x402-cardano-demo/facilitator/src/main/java/org/x402cardano/facilitator/**` (proven Java port), TS reference `x402/typescript/packages/mechanisms/cardano/src/**` (canonical), scheme spec `x402/specs/schemes/exact/scheme_exact_cardano.md`.
- Build commands always run with `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew ...`.
- **Preprod E2E credentials** (owner-provided, testnet-only): Blockfrost project id `preprodwv4rjfmnCJsuYNpZWGb9zBAfvoRH7T22`; mnemonic `base sun bonus asset priority twenty puppy rural animal public rural symbol tilt crowd grape claim fury satisfy wing churn ginger essence cigar nasty`. Read from env `BLOCKFROST_PROJECT_ID` / `E2E_MNEMONIC` with these as coded defaults **in the E2E main class only** (never in production config). *Documented deviation from the spec's env-only secrets rule (§13): explicit owner directive — the test class "must run the example fully" with these values; preprod-only, recommend rotation after the exercise.*

---

## Phase P1 — Skeleton, protocol model, `/supported` (spec M1)

### Task 1.1: Gradle project + Spring Boot app + Log4j2

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradle.properties`, `gradle/wrapper/*` (copy from `x402-cardano-demo/facilitator/gradle/`), `gradlew`, `gradlew.bat`
- Create: `src/main/java/org/cardanofoundation/x402/facilitator/FacilitatorApplication.java`
- Create: `src/main/resources/application.yml`, `src/main/resources/log4j2-spring.xml`
- Test: `src/test/java/org/cardanofoundation/x402/facilitator/FacilitatorApplicationTest.java`, `src/test/resources/application-test.yml`

**Interfaces produced:** runnable Spring Boot app on port 4022; `@ConfigurationProperties(prefix="x402") X402Properties` (Task 1.3).

`build.gradle` (complete):

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.16'
    id 'io.spring.dependency-management' version '1.1.7'
}
group = 'org.cardanofoundation'
version = '0.1.0'
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories { mavenCentral() }

configurations.configureEach {
    exclude group: 'org.springframework.boot', module: 'spring-boot-starter-logging'
}

ext {
    yaciStoreVersion = '2.0.2'
    cardanoClientVersion = '0.7.2'   // pinned to yaci-store 2.0.2's transitive version (spec §4.1)
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-log4j2'
    runtimeOnly 'org.apache.logging.log4j:log4j-layout-template-json'   // JsonTemplateLayout (json-logs profile)
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
    runtimeOnly 'org.postgresql:postgresql'
    runtimeOnly 'com.h2database:h2'   // test profile only
    implementation 'io.micrometer:micrometer-registry-prometheus'
    implementation "com.bloxbean.cardano:cardano-client-lib:${cardanoClientVersion}"
    implementation "com.bloxbean.cardano:cardano-client-backend-blockfrost:${cardanoClientVersion}"
    // P6 adds yaci-store starters here (see Task 6.1)
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.testcontainers:postgresql'
    testImplementation 'org.testcontainers:junit-jupiter'
}
tasks.named('test') { useJUnitPlatform() }
```

`FacilitatorApplication.java`:

```java
package org.cardanofoundation.x402.facilitator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FacilitatorApplication {
    public static void main(String[] args) { SpringApplication.run(FacilitatorApplication.class, args); }
}
```

`application.yml` core (P1 subset; grows per phase):

```yaml
server.port: 4022
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/facilitator}
    username: ${DB_USER:facilitator}
    password: ${DB_PASSWORD:facilitator}
  flyway:
    locations: classpath:db/migration
    default-schema: facilitator
    create-schemas: true
x402:
  networks:
    - id: cardano:preprod
      required: true
      chain:
        mode: blockfrost
        blockfrost:
          base-url: https://cardano-preprod.blockfrost.io/api/v0
          project-id: ${BLOCKFROST_PROJECT_ID:}
  verification: { max-tx-bytes: 32768, script-datum-policy: strict }
  settle:
    confirmation-timeout: 180s
    confirmation-depth: 1
    poll-interval: 3s
    accept-mempool: false
    idempotent-replay: false
    stability-window: 10m
    reconcile-horizon: 24h
    max-concurrent: 32
  duplicate-cache.ttl: 120s
  http.max-request-bytes: 65536
```

Test profile (`application-test.yml`): H2 in-memory (`jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`), `spring.flyway.default-schema: facilitator`, blank project id.

**Steps:**
- [ ] Copy wrapper from demo; write build files; write app class + yml + minimal `log4j2-spring.xml` (console pattern layout; JSON layout under a `json-logs` profile).
- [ ] Context-load test (`@SpringBootTest @ActiveProfiles("test")`) → run `./gradlew test` → PASS.

### Task 1.2: Protocol model (`model/protocol`) — exact demo/TS wire shapes

**Files:**
- Create under `src/main/java/org/cardanofoundation/x402/facilitator/model/protocol/`: `PaymentRequirements.java`, `PaymentPayload.java`, `ExactPayload.java`, `ResourceInfo.java`, `VerifyRequest.java`, `VerifyResponse.java`, `SettleRequest.java`, `SettleResponse.java`, `SupportedResponse.java`, `SupportedKind.java`
- Create `model/ErrorCodes.java` (all 30+ constants **verbatim** from demo `ErrorCodes.java` ∪ TS `constants.ts` superset incl. the two spec-added codes `invalid_exact_cardano_payload_script_datum_missing`, `invalid_exact_cardano_payload_payer_not_witness`)
- Create `service/registry/CardanoNetworks.java` (port demo verbatim: canonical ids, CIP-34 alias map, exact-match normalize, `networkId()`)
- Test: `model/protocol/ProtocolJsonTest.java`, `service/registry/CardanoNetworksTest.java` (port demo tests, adjust packages)

**Interfaces produced (exact — later tasks depend on these):**

```java
public record PaymentRequirements(String scheme, String network, String asset, String amount,
        String payTo, Integer maxTimeoutSeconds, Map<String,Object> extra) {}
public record ExactPayload(String transaction, String nonce) {}
public record PaymentPayload(Integer x402Version, ResourceInfo resource,
        PaymentRequirements accepted, ExactPayload payload) {}
public record VerifyRequest(Integer x402Version, PaymentPayload paymentPayload,
        PaymentRequirements paymentRequirements) {}
public record VerifyResponse(Boolean isValid, String invalidReason, String invalidMessage, String payer) {
    public static VerifyResponse valid(String payer);
    public static VerifyResponse invalid(String reason, String message); }
public record SettleResponse(Boolean success, String errorReason, String errorMessage, String payer,
        String transaction, String network, Map<String,String> extra) {
    public static SettleResponse ok(String txHash, String network, String payer, String status);
    public static SettleResponse fail(String reason, String message, String network);
    public static SettleResponse failWithTx(String reason, String txHash, String network, String payer, String status); }
```

Field names/casing MUST byte-match the demo's `protocol/*.java` (read them; they are the proven-compatible shapes) — **including** `PaymentPayload`'s optional `extensions` field and `SettleResponse.extra` as `Map<String,Object>` (object-valued, not `Map<String,String>`); the record sketches above are directional, the demo files are normative. Jackson config: global `NON_NULL` + `FAIL_ON_UNKNOWN_PROPERTIES=false` via a `Jackson2ObjectMapperBuilderCustomizer` in `config/JacksonConfig.java`.

- [ ] Port records + tests from demo → `./gradlew test` PASS.

### Task 1.3: Registry, config properties, controllers, error advice

**Files:**
- Create: `service/registry/SchemeNetworkFacilitator.java` (interface: `String scheme(); String caipFamily(); VerifyResponse verify(PaymentPayload, PaymentRequirements); SettleResponse settle(PaymentPayload, PaymentRequirements);`)
- Create: `service/registry/X402FacilitatorRegistry.java` (port demo: `register`, `find(x402Version, scheme, network)` v2-only, `supported()` — with signers key **`cardano:*`** and `extensions: []` per spec §5.3)
- Create: `config/X402Properties.java` (records mirroring the yml above: `List<NetworkEntry> networks`, `Verification`, `Settle`, `DuplicateCache duplicateCache`, `Http http`; `NetworkEntry(String id, boolean required, Chain chain)`, `Chain(String mode, Blockfrost blockfrost)`)
- Create: `config/StartupValidation.java` (`@Component implements InitializingBean`: ≤1 yaci-store network; `accept-mempool=true` + yaci light ⇒ fail fast (§9.3); every id normalizes to a supported network)
- Create: `controller/FacilitatorController.java` (`POST /verify`, `POST /settle`, `GET /supported` — port demo shapes: 200 with logical failure body; 400 on missing fields; 500 sanitized), `controller/HealthController.java` (`GET /health` skeleton), `controller/advice/ApiErrorAdvice.java` (`@RestControllerAdvice`: 500 → `{ "error": "internal_error", "correlationId": ... }`, detail logged only)
- Create: `config/RequestSizeFilter.java` (Content-Length fast-reject + byte-counting bounded stream → 413; limit `x402.http.max-request-bytes`)
- Test: `controller/FacilitatorControllerTest.java` (MockMvc: /supported shape incl. `cardano:*` signers key; /verify 400 on malformed; 500 sanitized), `config/StartupValidationTest.java`, `config/RequestSizeFilterTest.java` (chunked oversized body → 413 before Jackson)

Also create **once, here**: `service/registry/DefaultSchemeNetworkFacilitator.java` — the §4.3 façade. In P1 its `verify` and `settle` delegates both return a fixed `not_implemented`-style failure; P2 injects the real `ExactCardanoScheme` for verify; P3 injects `SettlementService` for settle. (No renames or re-creations later — this resolves the façade's phase ordering.)

- [ ] Implement with the façade registered for `cardano:preprod`; tests PASS.

**P1 gate:** `./gradlew build` green → **Codex review** (task prompt over the tree, scoped: wire-shape fidelity vs demo/TS, §5 conformance) → fix findings → AGREE.

---

## Phase P2 — Verification pipeline + Blockfrost backend (spec M2, §6)

### Task 2.1: Chain SPI + model types

**Files:** Create under `chain/`: `FacilitatorChainService.java`, `ProtocolParamsProvider.java`, `NetworkClock.java`, `ChainLookupException.java`, `SubmissionException.java` (unused after 2.4's classified results — delete if so); under `model/chain/`: `UtxoState.java`, `SubmissionResult.java`, `InclusionResult.java`, `BackendHealth.java`, `ProtocolParams.java`.

**Interfaces produced (exact, spec §9.1):**

```java
public sealed interface UtxoState {
    record Unspent(String ownerAddress) implements UtxoState {}
    record Spent() implements UtxoState {}
    record Unknown() implements UtxoState {} }
public sealed interface SubmissionResult {
    record Accepted(String txHash) implements SubmissionResult {}
    record Rejected(String cause) implements SubmissionResult {}
    record Unknown(String cause) implements SubmissionResult {}
    record NotSubmitted(String cause) implements SubmissionResult {} }
public sealed interface InclusionResult {
    record NotSeen() implements InclusionResult {}
    record Included(int depth, long slot, String blockHash) implements InclusionResult {} }
public interface FacilitatorChainService {
    UtxoState getUtxoState(String txHash, int index);         // throws ChainLookupException
    long getCurrentSlot();                                     // throws ChainLookupException (stale ⇒ throw)
    SubmissionResult submitTransaction(byte[] txBytes);
    InclusionResult checkInclusion(String txHash);             // throws ChainLookupException — error ≠ absence
    InclusionResult awaitInclusion(String txHash, int minDepth, Duration timeout);
    BackendHealth health(); }
public interface ProtocolParamsProvider { ProtocolParams current(); }   // ProtocolParams(BigInteger coinsPerUtxoByte, int maxTxSize)
public interface NetworkClock { long expectedSlotAt(Instant t); Instant slotToTime(long slot); }
```

`NetworkClock` impl (`chain/ShelleyNetworkClock.java`): per-network
**SlotConfig** constants `(zeroSlot, zeroTime, slotLengthMs=1000)` — ported
**at implementation time from the TS reference's slot-conversion code**
(the client signer converts POSIX-ms↔slot; grep
`typescript/packages/mechanisms/cardano/src` for the constants — do not
invent them), exposed as config defaults (`x402.networks[].slot-config`,
overridable). Backend-independent (no Blockfrost dependency — spec
requirement for yaci mode). Validation: a blockfrost-mode integration test
compares `expectedSlotAt(now)` against Blockfrost's latest-block slot
(tolerance ± a few slots).

### Task 2.2: Transaction decoder (Stage B) — port from demo

**Files:** Create `service/verification/decoder/CardanoTransactionDecoder.java`, `model/verification/DecodedTransaction.java`; Test `.../decoder/CardanoTransactionDecoderTest.java` + `testutil/TestTx.java` (port demo's deterministic fixture builder wholesale, adjust package).

Port demo `cardano/CardanoTransactionDecoder.java` **verbatim-plus**: tx hash = blake2b-256 over raw body bytes (`TransactionUtil.getTxHash(raw)`), raw-CBOR body-key inspection for real ttl/validityStart presence (`ttl=0` vs absent), vkey/bootstrap/script witness counts, per-vkey Ed25519 verification. **Additions vs demo:** expose `Set<String> verifiedWitnessKeyHashes` (blake2b-224 of each verified vkey witness's public key — for D5) and `serializedSize`. Port the demo's full decoder test class; add tests for the two new fields.

### Task 2.3: Blockfrost chain backend

**Files:** Create `chain/blockfrost/BlockfrostChainService.java`, `chain/blockfrost/BlockfrostProtocolParamsProvider.java`; Test with Mockito-mocked `BFBackendService` (port demo's `BlockfrostChainServiceTest` cases: unspent/spent/never-existed/429).

Port demo semantics: `getUtxoState` = `getTxOutput(txHash, index)` (404 ⇒ `Unknown`… **careful**: demo mapped 404 ⇒ never existed; our tri-state maps 404 ⇒ `Spent()`? No — spec D2 folds spent∪never-existed into one verdict (`nonce_not_on_chain`), so map: 404 on the outref ⇒ `Spent()` (semantically "not in live set"); found owner + outref present in owner's live UTxO pages ⇒ `Unspent(owner)`; owner found but outref absent from live set ⇒ `Spent()`; provider errors/429 ⇒ `ChainLookupException`. `Unknown()` is reserved for the yaci sync-horizon case (P6). Submission: `POST /tx/submit` via `TransactionService.submitTransaction(bytes)` — map HTTP-level validation failure ⇒ `Rejected(cause)`, transport/timeouts ⇒ `Unknown(cause)`, success ⇒ `Accepted(hash.toLowerCase())`. `checkInclusion`: `getTransaction(txHash)` → found ⇒ depth = latestBlockHeight − txBlockHeight + 1; 404 ⇒ `NotSeen`; other errors ⇒ throw. `awaitInclusion`: poll `checkInclusion` at `poll-interval` until depth ≥ minDepth or timeout; transient errors retried within the window. Params provider: `/epochs/latest/parameters` cached 15 min (`coinsPerUtxoSize`, `maxTxSize`).

### Task 2.4: `ExactCardanoScheme` — Stage A–E (default method) + method SPI

**Files:** Create `service/verification/ExactCardanoScheme.java`, `service/verification/VerificationContext.java` (record: decoded tx, requirements, payload, payer, chain services), `service/verification/method/TransferMethodVerifier.java` (interface: `String method(); Optional<VerifyResponse> verify(VerificationContext ctx);` — empty = pass), `method/DefaultTransferVerifier.java`, `service/verification/MinUtxoCalculator.java`; Test `ExactCardanoVerifyTest.java` — **port the demo's entire branch matrix** (every error code) + new checks below, using `TestTx` + a `FakeChainService` test double (port from demo).

Pipeline order (§6, exactly — first failure wins):
A1 version=2 (`UNSUPPORTED_VERSION`) → A2 scheme registered (`unsupported_scheme`) → A3 network normalize+match (`network_mismatch`) → A4 payload well-formed: base64 decodes, decoded ≤ max-tx-bytes, nonce regex `^[0-9a-f]{64}#\d+$` (`invalid_exact_cardano_payload` / `..._nonce_invalid`) → A5 amount positive-integer string, asset regex (`lovelace` | `^[0-9a-f]{56}\.[0-9a-f]{0,64}$`), payTo bech32-parses **and network tag matches** (`invalid_exact_cardano_payload`) → B1–B4 decode/networkId/unsigned/signatures (codes per §6) → C1 ttl **>** currentSlot else `..._ttl_expired`; C2 validityStart ≤ currentSlot else `..._not_yet_valid`; C3 serializedSize ≤ min(cap, params.maxTxSize) (`invalid_exact_cardano_payload`) → D1 nonce ∈ inputs (`..._nonce_not_in_inputs`) → D2/D3 UTxO states: `Unknown` for the nonce **or any other input** under `utxo-unknown-policy=fail` ⇒ retryable `exact_cardano_facilitator_chain_lookup_failed` (deterministic verdicts for `Unknown` only under `reject-stale`, §9.3); nonce `Spent` ⇒ `..._nonce_not_on_chain`; other input `Spent` ⇒ `..._input_not_available` → D4 payer := owner → D5 payer credential: key ⇒ keyHash ∈ verifiedWitnessKeyHashes; script ⇒ scriptWitnessCount ≥ 1; Byron ⇒ reject (`invalid_exact_cardano_payload_payer_not_witness`) → Stage E dispatch on `extra.assetTransferMethod` (default `"default"`; unknown ⇒ `unsupported_scheme`).

DefaultTransferVerifier (E1–E4): find output paying `payTo` (`..._recipient_mismatch`); exact asset (`..._asset_mismatch`); value ≥ amount (`..._amount_insufficient`); output lovelace ≥ `(160 + serializedOutputSize) * coinsPerUtxoByte` (`..._min_utxo_insufficient`). Wrap pipeline in try/catch ⇒ `invalid_exact_cardano_payload_verification_error`; `ChainLookupException` ⇒ `exact_cardano_facilitator_chain_lookup_failed`.

Wire `ExactCardanoScheme.verify` into a real `SchemeNetworkFacilitator` bean (replaces P1 stub) built per network entry by `config/ChainBackendFactory.java`.

**P2 gate:** full unit matrix green (`./gradlew test`) → **Codex review** (pipeline vs TS `scheme.ts` + scheme spec rules 1–7; boundary semantics ttl==slot; D5) → fix → AGREE.

---

## Phase P3 — Settlement + Postgres journal (spec M3, §7/§8)

### Task 3.1: Journal schema + repository

**Files:** Create `src/main/resources/db/migration/V1__settlement.sql` (§8 DDL verbatim, table `facilitator.settlement` — plus `attempt_id uuid`, `requirements_digest`, `tx_ttl_slot`, `response_json jsonb`; H2-compat: use `json` type via vendor placeholder or `text` column — choose `text` storing JSON for cross-db), `model/entity/SettlementRecord.java`, `repository/SettlementRepository.java` (Spring `JdbcTemplate`-based DAO — explicit SQL for fenced CAS, no JPA):

```java
public interface SettlementRepository {
    Optional<SettlementRecord> find(String txHash);
    boolean insertClaim(SettlementRecord r);                                    // INSERT ... ON CONFLICT DO NOTHING
    boolean casTransition(String txHash, UUID attemptId, String fromStatus, String toStatus,
                          Consumer<MapSqlParameterSource> extraSets);           // UPDATE ... WHERE tx_hash=? AND attempt_id=? AND status=?
    boolean reclaim(String txHash, UUID newAttemptId, Duration claimTtl);       // stale CLAIMED or FAILED/EXPIRED
    List<SettlementRecord> dueForReconcile(int limit); }
```

Statuses: `CLAIMED, SUBMITTING, SUBMITTED, NOT_CONFIRMED, CONFIRMED, FAILED, EXPIRED`. Migration SQL uses **fully-qualified** `facilitator.` object names + `CREATE SCHEMA IF NOT EXISTS facilitator` (so it coexists with yaci-store's own unqualified migrations in schema `store` under one Flyway run — mode-aware Flyway wiring is owned by Task 6.1). Tests: H2 unit tests for fast CAS-fencing iteration (wrong attempt_id ⇒ 0 rows; reclaim windows) **plus the spec-mandated Testcontainers PostgreSQL suite** (§15): real `ON CONFLICT` claim atomicity with **two Spring contexts against one Postgres container** (concurrent 8-thread settle race across contexts ⇒ exactly one submit), advisory-lock reconciler exclusivity, JSON column round-trip.

### Task 3.2: SettlementService (§7 pipeline) + façade split

**Files:** Create `service/settlement/SettlementService.java`, `service/settlement/SettlementDigest.java` (SHA-256 over canonical JSON of requirements + `payload.resource` url — canonical = Jackson with sorted keys), refactor `service/registry/DefaultSchemeNetworkFacilitator.java` (façade: verify→scheme, settle→settlementService per §4.3). Test `ExactCardanoSettleTest.java` — port demo's settle matrix (happy, verify-fail short-circuit, duplicate, 8-thread race ⇒ 1 submit, release-on-reject, keep-on-timeout, TTL reclaim) against H2 + `FakeChainService`, plus new: journal-lookup-first branches (§7.1 incl. digest mismatch falls through; CLAIMED ⇒ duplicate; EXPIRED re-check), NOT_SUBMITTED release, UNKNOWN keeps SUBMITTING, idempotent-replay off by default (flag on ⇒ replay path re-runs A/B/D1/D5 — implement `replayProfileCheck()` calling into scheme's stage-A/B methods).

Order per §7 (1 journal lookup → 2 re-verify → 3 claim(attempt) → 4 SUBMITTING→submit classified → 5 awaitInclusion(depth) → 6 timeout ⇒ NOT_CONFIRMED (no extra.status) → response). `extra.status="confirmed"` only on depth-satisfied inclusion; `accept-mempool=true` ⇒ immediate success after `Accepted` (blockfrost mode). Replay profile (flag on) = Stages A+B, D1 + nonce-vs-journaled-`nonce_outref`, D5-vs-journaled-payer, **plus the §7.1 stability re-check**: `confirmed_at` younger than `stability-window` ⇒ one-shot `checkInclusion` must show depth ≥ `confirmation-depth`, else fenced CAS demotion `CONFIRMED→SUBMITTED` + `settlement_not_confirmed` response.

**Flyway (two runners, stable histories)**: `config/FlywayConfig.java` disables Spring's single auto-run and runs two programmatic Flyway instances — facilitator migrations always (`classpath:db/migration`, schema `facilitator`, its own `facilitator.flyway_schema_history`), yaci-store's only when a yaci network is configured, with the `{vendor}` placeholder **resolved explicitly from the DataSource's product name** (postgresql → `classpath:db/store/postgresql`, h2 → `classpath:db/store/h2`; programmatic Flyway does not expand Spring's `{vendor}` token) — covered by tests on both vendors. Mode switches can never re-run or orphan either history.

### Task 3.3: Reconciler + health/readiness + metrics

**Files:** Create `service/settlement/SettlementReconciler.java` (`@Scheduled(fixedDelay=30s)`, Postgres advisory lock `pg_try_advisory_lock` — skip on H2; sweeps SUBMITTING/SUBMITTED/NOT_CONFIRMED via one-shot `checkInclusion` ⇒ CONFIRMED@depth / EXPIRED past `tx_ttl_slot`+margin / TTL-less past reconcile-horizon; recent CONFIRMED stability re-check + demote), `config/HealthConfig.java` (readiness contributor per §5.4: DB + required networks' backend health), Micrometer counters (`x402_verify_total{outcome,reason}`, `x402_settle_total`, provider failures). Tests: reconciler promote/expire/demote paths with FakeChainService; lookup-error preserves state.

**P3 gate:** all green incl. a two-context H2 duplicate-claim test → **Codex review** (state machine vs §7/§8; races; fencing) → fix → AGREE.

---

## Phase P4 — E2E preprod proof (the goal's acceptance test)

### Task 4.1: E2E main class

**Files:** Create `src/test/java/org/cardanofoundation/x402/facilitator/e2e/X402PreprodE2E.java` (a `public static void main` class, run via a dedicated Gradle task `e2e`, NOT part of `test`):

```java
package org.cardanofoundation.x402.facilitator.e2e;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.util.Base64;
import java.util.Map;

/** Full x402 flow on preprod: build+sign (cardano-client-lib) -> /verify -> /settle -> on-chain check. */
public class X402PreprodE2E {
    static final String BF = env("BLOCKFROST_PROJECT_ID", "preprodwv4rjfmnCJsuYNpZWGb9zBAfvoRH7T22");
    static final String MNEMONIC = env("E2E_MNEMONIC",
        "base sun bonus asset priority twenty puppy rural animal public rural symbol tilt crowd grape claim fury satisfy wing churn ginger essence cigar nasty");
    static final String FACILITATOR = env("FACILITATOR_URL", "http://localhost:4022");
    static final BigInteger AMOUNT = new BigInteger(env("E2E_AMOUNT_LOVELACE", "1500000"));

    public static void main(String[] args) throws Exception {
        var backend = new BFBackendService("https://cardano-preprod.blockfrost.io/api/v0/", BF);
        var account = new Account(Networks.preprod(), MNEMONIC);
        String from = account.baseAddress();
        // receiver = same wallet, address index 1 (Account.java:242 indexed factory — getBaseAddress(int) does NOT exist in 0.7.2)
        String payTo = Account.createFromMnemonic(Networks.preprod(), MNEMONIC, 0, 1).baseAddress();

        long tip = backend.getBlockService().getLatestBlock().getValue().getSlot();
        long ttl = tip + 600;

        // 1. Build + sign the payment (client pays fee; facilitator never signs)
        var quickTx = new QuickTxBuilder(backend);
        Tx tx = new Tx().payToAddress(payTo, Amount.lovelace(AMOUNT)).from(from);
        Transaction signed = quickTx.compose(tx)
                .validTo(ttl)
                .withSigner(SignerProviders.signerFrom(account))
                .buildAndSign();
        byte[] txBytes = signed.serialize();
        String txB64 = Base64.getEncoder().encodeToString(txBytes);
        var in0 = signed.getBody().getInputs().get(0);
        String nonce = in0.getTransactionId().toLowerCase() + "#" + in0.getIndex();

        // 2. x402 verify + settle
        var requirements = Map.of("scheme","exact","network","cardano:preprod","asset","lovelace",
                "amount", AMOUNT.toString(), "payTo", payTo, "maxTimeoutSeconds", 600,
                "extra", Map.of("assetTransferMethod","default"));
        var body = Map.of("x402Version", 2,
                "paymentPayload", Map.of("x402Version", 2,
                        "resource", Map.of("url","https://example.test/report","description","e2e"),
                        "accepted", requirements,
                        "payload", Map.of("transaction", txB64, "nonce", nonce)),
                "paymentRequirements", requirements);

        var om = new ObjectMapper(); var http = HttpClient.newHttpClient();
        JsonNode verify = post(http, om, FACILITATOR + "/verify", body);
        System.out.println("verify: " + verify);
        require(verify.path("isValid").asBoolean(), "facilitator rejected: " + verify);
        // (settle below tolerates settlement_not_confirmed by falling through to
        //  the on-chain poll for the LOCAL hash — see the require() replacement)

        String expectedHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                .getTxHash(txBytes);   // ledger tx id computed LOCALLY — the settle response must match it

        JsonNode settle = post(http, om, FACILITATOR + "/settle", body);
        System.out.println("settle: " + settle);
        boolean settled = settle.path("success").asBoolean();
        String notConfirmed = "exact_cardano_settlement_not_confirmed";
        if (!settled && !notConfirmed.equals(settle.path("errorReason").asText())) {
            throw new IllegalStateException("settle failed: " + settle);   // hard failure — not a timeout
        }
        if (settled) {
            require(expectedHash.equalsIgnoreCase(settle.path("transaction").asText()),
                    "facilitator returned a DIFFERENT tx hash: " + settle.path("transaction").asText()
                            + " expected " + expectedHash);
        } else {
            System.out.println("settle timed out at the facilitator; tx was submitted — polling chain directly");
        }

        // 3. Independent on-chain proof via Blockfrost, using the LOCALLY computed hash
        //    (never the facilitator's word for it). Poll up to 5 minutes.
        long deadline = System.currentTimeMillis() + 300_000;
        String block = null;
        while (System.currentTimeMillis() < deadline) {
            var onChain = backend.getTransactionService().getTransaction(expectedHash);
            if (onChain.isSuccessful() && onChain.getValue().getBlock() != null) {
                block = onChain.getValue().getBlock();
                break;
            }
            Thread.sleep(5000);
        }
        require(block != null, "tx not on-chain within 5 min: " + expectedHash);
        System.out.println("ON-CHAIN CONFIRMED: tx " + expectedHash + " in block " + block
                + " (https://preprod.cardanoscan.io/transaction/" + expectedHash + ")");
    }
    // post(): send JSON, parse JSON; require(cond,msg): throw IllegalStateException when false; env(): getenv with default.
}
```

(`post`/`require`/`env` helpers are ~15 lines, written in the class.) Gradle task:

```groovy
tasks.register('e2e', JavaExec) {
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'org.cardanofoundation.x402.facilitator.e2e.X402PreprodE2E'
}
```

### Task 4.2: Run the proof

- [ ] `docker compose -f deploy/docker-compose.yml up -d postgres` (write minimal compose with postgres service first if P6 not yet done).
- [ ] Start app: `BLOCKFROST_PROJECT_ID=preprod... ./gradlew bootRun` (background), wait for readiness.
- [ ] Preflight: wallet funded? `curl` Blockfrost address endpoint for the mnemonic's base address; if empty, report and use https://docs.cardano.org/cardano-testnets/tools/faucet — expected funded (owner-provided).
- [ ] `./gradlew e2e` → expect `ON-CHAIN CONFIRMED`. If `/settle` returns `settlement_not_confirmed` at 180 s (rare; preprod blocks ≈ 20 s): the tx was already submitted — poll Blockfrost for the **locally computed hash** until it appears in a block, then the on-chain proof stands (do NOT re-run `/settle` expecting success: with `idempotent-replay=false` a duplicate returns `duplicate_settlement` by design).
- [ ] **This is the goal's success criterion** — verify (a) facilitator accepted, (b) tx really on-chain (independent Blockfrost lookup + print Cardanoscan link).

**P4 gate:** Codex review of the E2E class + a re-run if it flags correctness issues.

---

## Phase P5 — Masumi + script methods (spec M5, TS parity)

### Task 5.1: Masumi verifier (M1–M9)

**Files:** Create `service/verification/method/masumi/MasumiTransferVerifier.java`, `masumi/MasumiDatum.java` (19-field Constr-0 parser, structural comparisons), `masumi/MasumiConfig.java` (`x402.masumi.allowed-script-hashes.<network>`, default verified v2 hash `a15ce9d82d2f67645fc624e2edac03c6f1c106d0ad1af5815a3b14ad` for mainnet+preprod); Test: port demo `MasumiTransferVerifierTest` matrix + new-work negatives (reference script, collateral bounds 0∨≥1,435,230∧≤locked, deadline slot→ms via NetworkClock ≤ pay_by_time, post-result min-UTxO floor, return-address presence/absence, required-`extra` presence per TS `CardanoExtraMasumi` optionality, defaulted fields inputHash→empty / collateralReturnLovelace→0).

Porting source: TS `exact/masumi/verify.ts` (canonical, audit-verified) + demo subset. Structural credential/BigInteger comparisons only — never hex-string equality.

### Task 5.2: Script verifier (S1–S3)

**Files:** Create `method/script/ScriptTransferVerifier.java`, `script/ScriptAddressDeriver.java` (from `extra.scriptHash` or `extra.script{type,code}`; parameterized: apply `extra.parameters` in JS `Object.values` enumeration order — integer-like keys first numerically — via `aiken-java-binding` `applyParams`; conformance vectors extracted from TS `scriptAddress.test.ts` fixtures), datum policy per §6 S3 (V1 datum-hash; V2 datum required; V3 per `script-datum-policy`; scriptHash-only ⇒ some datum). New error codes from spec. Tests: derivation vectors incl. integer-key ordering, per-version datum negatives.

**P5 gate:** Codex review (byte-level parity vs `verify.ts`/`scriptAddress.ts`) → fix → AGREE.

---

## Phase P6 — yaci-store backend + Compose (spec M4/§12)

### Task 6.1: yaci backend (store starters, trackers, era-correct submission)

**Files:** add yaci-store starters (core/blocks/utxo/transaction/epoch/submit) to `build.gradle`; create `chain/yaci/` — `YaciChainService.java` (utxo store lookups w/ tri-state + staleness gate; `Unknown()` per sync horizon + `utxo-unknown-policy`), `ChainTipTracker.java`, `TxInclusionTracker.java` (port demo, add depth + transaction-store backing + journal demotion hook), `EraResolver.java` (tip-bounded era from era store; `x402.chain.era-override`), `N2NTxSubmitter.java` (era-correct adapter replacing stock agent messages), `SyncFromTipPostProcessor.java` (TipFinder-based), full-variant N2C submission via `TxSubmissionService` bean. Conditional on any network `mode: yaci-store` (`@ConditionalOnProperty`/custom condition).
**Flyway ownership (yaci mode)** — superseded by Task 3.2's two-runner `FlywayConfig` (separate histories; no mode-switch hazard); this task only enables the store runner. **Dormancy is explicit, not assumed**: yaci-store starters auto-configure unconditionally (verified: `BlocksStoreAutoConfiguration` imports its configs with no condition), so blockfrost-only deployments exclude the yaci auto-configurations (`spring.autoconfigure.exclude` list assembled by a custom `EnvironmentPostProcessor`) and yaci mode re-enables them; `SyncFromTipPostProcessor` registers via `META-INF/spring.factories` (demo pattern). Also owned here: `chain/yaci/YaciProtocolParamsProvider.java` (computes the current epoch **from the indexed best-chain tip**, calls `EpochParamService.getProtocolParams(epoch)` for that exact epoch, and fails closed (`ChainLookupException`) when that epoch's params are absent — never "latest row whatever it is"; freshness-gated like all yaci reads) and the §5.4 readiness contributors for yaci mode (slot-clock freshness + catch-up state + N2C/N2N submission connectivity probe + current-epoch `EpochParam` present).

Tests: event-driven tracker tests (port demo), era resolution incl. cross-era rollback, unknown-policy mapping, **and the spec M4 exit criteria (mandatory, not best-effort): Conway-era devnet submission conformance on both the N2C and N2N paths via yaci-devkit, plus the full x402 devnet E2E** — with an explicit **devnet mapping applied identically on both sides**: the facilitator's devkit config uses the logical x402 network `cardano:preview` (ledger networkId 0 and `addr_test` prefix — matching every A5/B2/D5 check on a devnet) with the per-network `slot-config` override (already in `X402Properties.NetworkEntry`) set from the devkit devnet's genesis (`zeroSlot 0`, `zeroTime` = devnet `systemStart`, `slotLength 1000`), and `store.cardano.protocol-magic` = the devkit magic; the E2E harness passes the same `E2E_NETWORK=cardano:preview`, uses `E2E_BACKEND_URL` (the devkit's Blockfrost-compatible yaci-store API) for building **and** the independent inclusion lookup, and a funded devkit account (`E2E_MNEMONIC`; testnet address derivation is magic-independent). One harness thus runs both the preprod proof and the devkit E2E (build/sign → `/verify` → `/settle` → confirmed) against a yaci-mode facilitator, and readiness/slot-clock math is correct because the override feeds the same `NetworkClock` used by staleness checks. If the devkit cannot run in this environment, that is a reported blocker for M4 completion, never a silent skip.

### Task 6.2: Docker Compose profiles

**Files:** `deploy/docker-compose.yml` — profiles: `light` = postgres + facilitator (blockfrost or yaci-light env); `full` additionally: `mithril-sync` (one-shot, `restart: "no"`, hardened entrypoint per §12) → shared **node-db volume** → `cardano-node` (`ghcr.io/intersectmbo/cardano-node`, pinned tag; `depends_on: mithril-sync: condition: service_completed_successfully`; healthcheck) → shared **ipc volume** (N2C socket) → facilitator (`store.cardano.n2c-node-socket-path` mounted; `depends_on: cardano-node: condition: service_healthy`). **Node configuration is owned here too** (the rosetta node entrypoint proves the node needs `config.json` + `topology.json`): `deploy/node-config/<network>/` with the official IOG config set (config, topology, genesis files — downloaded from book.world.dev.cardano.org env configs at build time or committed), mounted read-only into the node service with the matching `--config`/`--topology` command. Plus `deploy/mithril/Dockerfile` + `entrypoint.sh` (rosetta-java pattern + §12 hardening: first-boot failure ⇒ non-zero; staged partial downloads; marker only after verified restore), `deploy/.env.example`, app `Dockerfile` (two-stage temurin-21).

**P6 gate:** `docker compose config` valid for BOTH profiles; light profile boots against Postgres; **full-profile runtime smoke on preprod**: `docker compose --profile full up` runs Mithril restore → node start → facilitator readiness (N2C socket reachable) at least once end-to-end (long-running download acceptable; report duration), not just a config review; Codex final full-tree review → AGREE.

---

## Phase P7 — §13 hardening (spec M6)

### Task 7.1: resource protection + ops polish

**Files:** Create `config/SettlementGate.java` (semaphore `x402.settle.max-concurrent`, saturated ⇒ 503 `Retry-After`), `chain/blockfrost/ProviderBudget.java` (per-request call budget + global bulkhead semaphore ⇒ `CHAIN_LOOKUP_FAILED` on exhaustion), `config/RateLimitFilter.java` (bucket4j — **dependency added here with an explicit pinned version** resolved from Maven Central at implementation time (`com.bucket4j:bucket4j_jdk17-core:<pin>`; Boot's BOM does not manage bucket4j), default-on generous per-IP), optional `config/ApiKeyFilter.java` (`x402.security.settle-api-key`, off by default), CORS closed (`config/WebConfig.java`), `spring.threads.virtual.enabled: true` in yml (verify HikariCP pinning note §13), **Jackson depth/size limits** (`StreamReadConstraints` via the builder customizer: max nesting 64, max string ≈ max-request-bytes), **correlation id**: servlet filter generates/propagates `X-Correlation-Id` into Log4j2 `ThreadContext` (pattern already prints `%X{correlationId}`), **metrics completeness** per §13 (`x402_verify_total{outcome,reason,network}`, `x402_settle_total{...}`, latencies timers, chain-backend call/failure counters, `x402_sync_lag_slots` gauge (yaci), duplicate-claim hits, protocol-params age), `deploy/README.md` (operator docs: profiles, env vars, rate-limit/proxy guidance), and a written mainnet-readiness checklist mapped to spec §14. Tests: gate saturation ⇒ 503; budget exhaustion ⇒ chain_lookup_failed; rate-limit 429; correlation id echoed in logs.

**P7 gate:** full `./gradlew build` + Codex final review of the whole tree vs spec §13/§14 → AGREE.

---

## Self-review checklist (run after writing, before Codex)

1. Spec coverage: §5 API ✔ (1.3), §6 pipeline ✔ (2.4, 5.1, 5.2), §7/§8 ✔ (3.1–3.3), §9 ✔ (2.1, 2.3, 6.1), §10 ✔ (1.3 registry + startup validation), §11 ✔ (yml + properties), §12 ✔ (6.2), §13 ✔ (1.1 log4j2, 1.3 filter/advice, 3.3 metrics; virtual threads: add `spring.threads.virtual.enabled: true` to yml in 1.1), §14 remediations land in their owning tasks, §15 test matrix distributed across tasks, E2E ✔ (4.x).
2. Placeholder scan: none — every task names exact files, sources to port, and expected checks.
3. Type consistency: `SchemeNetworkFacilitator`/`VerifyResponse`/`SettleResponse`/`UtxoState`/`SubmissionResult` signatures defined once (1.2/1.3/2.1) and referenced identically in 2.4/3.2/6.1.
