# Cardano x402 Facilitator — Design Specification

**Date:** 2026-07-16
**Status:** Reviewed — dual sign-off after 15 adversarial review rounds
(independent Claude reviewer + Codex GPT-5.6 Sol, both fact-checking against
the x402 spec/audit/TS reference/demo/yaci sources; final verdicts:
AGREE / AGREE. Rounds 1–6 covered the original draft; rounds 7–12 the first
owner revision — hybrid mode removed, yaci-store light/full variants,
Mithril bootstrap, Log4j2 — during which the reviewers surfaced, and
source-verification confirmed, the yaci 0.4.4 N2N era-mistagging defect now
handled by §9.3's adapter requirement; rounds 13–15 the second owner
revision — single Spring Boot project, layered `model`/`service`/
`controller` packages under `org.cardanofoundation.x402.facilitator`.)
**Repo:** `cardano-x402-facilitator` (this repository, currently empty)

---

## 1. Purpose and scope

A production-grade **x402 facilitator** for Cardano: an HTTP service that resource
servers call to (a) **verify** a client-supplied, fully-signed Cardano payment
transaction against declared `PaymentRequirements`, and (b) **settle** it by
submitting it to the network and reporting confirmation. The facilitator never
holds keys, never signs, and never pays fees — clients build and sign the entire
transaction; the facilitator is a verification + submission + attestation service
(x402 spec: `specs/schemes/exact/scheme_exact_cardano.md`, "the facilitator does
not require a funded wallet — only a provider connection").

In scope:

- x402 **v2** facilitator API: `POST /verify`, `POST /settle`, `GET /supported`.
- Scheme `exact` on `cardano:mainnet`, `cardano:preprod`, `cardano:preview`
  (CIP-34 aliases accepted as input and normalized).
- All three `assetTransferMethod`s of the Cardano exact scheme: `default`,
  `masumi`, `script` (the demo implements only the first two; the TS reference
  implements all three).
- Complete verification rule set (the spec's seven facilitator rules plus the
  structural/signature checks the TS + demo implementations perform), including
  the audit-driven hardening described in §14.
- Modular chain access: **yaci-store** (embedded indexer + N2C `txSubmission`)
  or **Blockfrost** (lightweight), selected by configuration.
- **PostgreSQL** persistence (yaci-store stores + facilitator's own settlement
  journal / duplicate-settlement guard).
- **Docker Compose** deployment.

Out of scope (see §17): payer-side/client SDK, resource-server middleware,
schemes other than `exact` (`upto`, `batch-settlement`, `auth-capture` have no
Cardano binding), fee sponsorship (explicitly unsupported by the scheme spec),
Masumi escrow *release/dispute* flows (only the lock is verified), x402 **v1**
wire compatibility (the Cardano scheme exists only in v2).

## 2. Source material (verified)

| Source | What was taken |
|---|---|
| `x402/specs/x402-specification-v2.md` | Facilitator role, v2 envelope, error semantics |
| `x402/specs/schemes/exact/scheme_exact_cardano.md` | The seven verification rules, Masumi rules, script rules, payload format, network ids, settlement semantics, duplicate-settlement guidance |
| `x402/specs/transports-v2/http.md` | `PAYMENT-REQUIRED` / `PAYMENT-SIGNATURE` / `PAYMENT-RESPONSE` headers (context only; the facilitator API itself is plain JSON) |
| `x402/CARDANO_AUDIT.md` | Findings C1–C2, H1–H2, M1–M7, L1–L7, I1–I11 → remediation matrix in §14 |
| `x402/typescript/packages/mechanisms/cardano/` | Reference implementation (error-code catalogue, `constants.ts` values) |
| `x402/java` @ git `2fddf15f` | Deleted-but-recoverable v2 `org.x402.cardano` DTO/constants package (skeleton to revive) |
| `x402-cardano-demo/facilitator` | Working Java port of the TS verify/settle pipeline (Spring Boot 3.4.5, yaci-store 2.0.2, cardano-client-lib 0.7.2) — the starting baseline |
| Maven Central / bloxbean/yaci-store GitHub | `yaci-store-submit` 2.0.2 exists; submits via N2C `LocalTxSubmissionClient`, gated on `store.cardano.n2c-node-socket-path` or `store.cardano.n2c-host` |
| github.com/cardano-foundation/cardano-rosetta-java `docker/dockerfiles/mithril` | Mithril snapshot-bootstrap pattern for the Compose `full` profile (§12) |

Facts deliberately **not** trusted from memory and verified during design:
yaci-store module list on Maven Central (incl. `submit`, `utxo`, `transaction`,
`epoch`), Blockfrost endpoints (`POST /tx/submit` with `application/cbor`,
`GET /addresses/{addr}/utxos`, `GET /txs/{hash}` carries **no** confirmations
field — depth must be computed against the latest block), Spring Boot release
lines (4.1 is current; yaci-store 2.0.2 is built against Boot 3.3.x → we stay on
**3.5.x** until yaci-store supports Boot 4), yaci's N2N `TxSubmissionClient`
(exists in `yaci/helper`, javadoc-marked *"still under development"*, no
validation verdict by protocol design, and — verified in yaci 0.4.4 source —
its agent era-mistags replies as hardcoded `Era.Babbage`, breaking
Conway-era submission: basis for §9.3's light-variant
submission caveats and adapter requirement), and cardano-rosetta-java's Mithril bootstrap
(`docker/dockerfiles/mithril` Dockerfile + entrypoint read in full — basis
for §12's `mithril-sync` service).

## 3. Requirements

Functional:

1. Implement the x402 v2 facilitator API for `exact` × {`cardano:mainnet`,
   `cardano:preprod`, `cardano:preview`} with **every verification** the
   TS reference + Cardano scheme spec define (§6).
2. Two interchangeable chain backends behind one SPI — exactly one owns all
   chain capabilities for a network (no split-backend/hybrid topology; the
   demo's composite is deliberately not carried over, §16.2):
   - **yaci-store mode** — embedded yaci-store starters index UTxOs/blocks/
     transactions/epoch params into Postgres via N2N chain-sync; verification
     answered from the local index. Two deployment variants:
     **full** — syncs from an operator-run local `cardano-node`
     (Mithril-bootstrapped, §12); submission through the `yaci-store-submit`
     module (N2C LocalTxSubmission against the node socket).
     **light** — no local node: syncs N2N from a **public relay** and submits
     via a facilitator-owned era-correct N2N adapter to that relay (§9.3 — with its
     documented trust and no-validation-feedback caveats).
   - **blockfrost mode** — all chain questions (UTxO status, tip slot, protocol
     parameters, submission, inclusion) via the Blockfrost API; no node needed.
3. PostgreSQL for **both** yaci-store data and the facilitator's own state
   (settlement journal, duplicate-settlement claims).
4. Docker Compose deployment for both topologies.
5. Multi-network: several (scheme, network) pairs may be served by one instance
   (constraint: at most one network can be in *yaci-store mode* per instance —
   the embedded store has a single sync pipeline; other networks fall back to
   Blockfrost backends or run as separate instances).

Non-functional:

- Horizontal scalability: no correctness-relevant in-memory-only state
  (duplicate guard moves to Postgres, §8).
- Observability: Prometheus metrics, structured logs, real health checks.
- Fail-closed verification: any chain-lookup uncertainty rejects (with a
  retryable error code), never approves.
- No custody: the process holds no signing keys.

## 4. Architecture

### 4.1 Package layout (single Spring Boot Gradle project, Java 21, Spring Boot 3.5.x)

One Gradle project (group `org.cardanofoundation`), conventional Spring Boot
layered layout. Root package **`org.cardanofoundation.x402.facilitator`**,
with the application class at the root and `model` / `service` / `controller`
plus the supporting packages the domain needs:

```
org.cardanofoundation.x402.facilitator/
├── FacilitatorApplication.java     # @SpringBootApplication (root package →
│                                   #   component scan covers everything below)
├── controller/                     # REST layer
│   ├── FacilitatorController       #   POST /verify, POST /settle, GET /supported
│   ├── HealthController            #   GET /health summary (§5.4)
│   └── advice/                     #   @RestControllerAdvice: sanitized errors,
│                                   #     correlation ids, 400/500 mapping (§5, §13)
├── model/                          # data types — no business logic
│   ├── protocol/                   #   v2 wire DTOs (records, Jackson NON_NULL,
│   │                               #     lenient): PaymentRequirements, PaymentPayload,
│   │                               #     Verify/Settle request+response, SupportedResponse.
│   │                               #     Revived from x402/java@2fddf15f + demo protocol pkg.
│   ├── chain/                      #   UtxoState (tri-state), SubmissionResult,
│   │                               #     InclusionResult, BackendHealth, ProtocolParams
│   ├── verification/               #   DecodedTransaction, VerificationResult
│   └── entity/                     #   facilitator.settlement journal row (§8)
├── service/                        # business logic
│   ├── verification/               #   ExactCardanoScheme (§6 pipeline),
│   │   ├── decoder/                #     CardanoTransactionDecoder (Stage B)
│   │   ├── method/                 #     TransferMethodVerifier SPI + Default/,
│   │   │                           #       Masumi/, Script/ verifiers (Stage E,
│   │   │                           #       Spring-injected beans)
│   │   └── MinUtxoCalculator
│   ├── settlement/                 #   SettlementService (§7 pipeline),
│   │                               #     SettlementJournal (fenced CAS, §8),
│   │                               #     Reconciler (scheduled, §8)
│   └── registry/                   #   X402FacilitatorRegistry, CardanoNetworks
│                                   #     (CIP-34 normalization), SchemeNetworkFacilitator
├── chain/                          # chain-access layer behind the §9.1 SPI
│   ├── FacilitatorChainService, ProtocolParamsProvider, NetworkClock
│   │                               #   (the three §9.1 contracts together —
│   │                               #     consumed by service.verification AND chain.yaci)
│   ├── blockfrost/                 #   Blockfrost implementation (§9.2)
│   └── yaci/                       #   embedded yaci-store implementation (§9.3):
│                                   #     trackers, era resolution, N2N submission
│                                   #     adapter, sync-from-tip post-processor
├── repository/                     # Spring Data repositories for model.entity
│                                   #   (Flyway: schema `facilitator`; yaci-store's
│                                   #   own migrations stay in schema `store`)
└── config/                         # X402Properties, ChainBackendFactory wiring
                                    #   (@ConditionalOnProperty, §4.2), startup
                                    #   validation (§10, §9.3), request-size filter
                                    #   (§13), Jackson/Log4j2/actuator/metrics setup
```

(`deploy/` — docker-compose.yml, profiles, `.env` templates — remains a
repo-level directory, not a Java package.)

Rationale: the layering keeps the same boundaries the multi-module draft had —
`model.protocol` and `service.verification` stay free of chain-backend
concerns, the §9.1 SPI still isolates `chain.blockfrost` from `chain.yaci`,
and backends remain swappable at configuration time (requirement 2) — but in
the conventional Spring Boot shape. One consequence accepted deliberately:
without build-level modules, the yaci-store starters are always **on the
classpath** even for a Blockfrost-only deployment; they are kept **dormant**
via conditional configuration (§4.2 — the demo proved property-gated
embedding of yaci-store in a single app). The cost is jar size, not behavior;
if it ever matters, extracting `chain.yaci` into an optional Gradle module is
a mechanical refactor the package boundary already prepares for.

Versions: Spring Boot **3.5.x** (constrained by yaci-store 2.0.2, which is
compiled against Boot 3.3.4 and proven on 3.4.5 in the demo; Boot 4.x adoption
is deferred until yaci-store publishes a compatible line). `cardano-client-lib`
pinned to the exact version yaci-store pulls transitively (0.7.2 for 2.0.2) —
the demo learned that a mixed cardano-client stack moves classes between
packages (`build.gradle` comment).

### 4.2 Chain backend selection

Backend mode is **per network entry** (§10), not global: a
`ChainBackendFactory` builds one chain-service graph per configured network
from `x402.networks[n].chain.mode: yaci-store | blockfrost`.
`@ConditionalOnProperty` gates only the *embedded yaci-store subsystem* (its
starters, sync pipeline, trackers), which is instantiated when at least one —
and at most one, enforced at startup (§10) — network entry requests
`yaci-store`. This resolves the obvious tension between a singleton mode
flag and per-network topologies: there is no global mode flag.

There is deliberately **no composite/failover backend** (the demo's
`CompositeChainService` — whose Blockfrost fallback was dead code — is not
carried over): each mode owns every chain capability outright and is
**fail-closed** on staleness (§9.3) rather than silently falling over to a
second provider. One backend per network keeps the health model, the trust
model, and the wiring trivially auditable.

### 4.3 Request flow

```
resource server ──POST /verify──▶ FacilitatorController
                                    └─▶ X402FacilitatorRegistry.find(v2, "exact", normalize(network))
                                          → SchemeNetworkFacilitator (same façade as below)
                                          └─▶ ExactCardanoScheme.verify(payload, requirements)
                                                ├─ structural checks (no I/O)
                                                ├─ CardanoTransactionDecoder (CBOR, signatures)
                                                ├─ FacilitatorChainService (slot, nonce UTxO, inputs)
                                                ├─ TransferMethodVerifier (default|masumi|script)
                                                └─ min-UTxO (ProtocolParamsProvider)
resource server ──POST /settle──▶ FacilitatorController
                                    └─▶ registry.find(...) → SchemeNetworkFacilitator (the façade:
                                          verify → ExactCardanoScheme; settle → SettlementService)
                                          └─▶ SettlementService (service.settlement — owns the §7 pipeline)
                                                ├─ journal lookup (idempotency/duplicate, digest-bound)
                                                ├─ re-verify: ExactCardanoScheme.verify(...)
                                                ├─ SettlementJournal.claim(txHash, attemptId)  [Postgres, fenced]
                                                ├─ FacilitatorChainService.submitTransaction (classified outcome)
                                                ├─ awaitInclusion(confirmation depth, rollback-aware)
                                                └─ journal transition → SettleResponse
```

Ownership is deliberate and the dependency graph is acyclic:
`SchemeNetworkFacilitator` (service.registry) is the façade holding **both**
the scheme (verify) and `SettlementService` (settle); `SettlementService`
depends one-way on `ExactCardanoScheme` for the §6 re-verification step; the
scheme itself never sees persistence — and never depends back on
`SettlementService`, so Spring constructor injection has no cycle.

## 5. API specification

All facilitator endpoints are plain JSON (the base64 header encodings of the
HTTP transport are between client and resource server; the resource server
unwraps them before calling us — same as demo/TS).

### 5.1 `POST /verify`

Request (`VerifyRequest`):

```json
{
  "x402Version": 2,
  "paymentPayload": {
    "x402Version": 2,
    "resource": { "url": "...", "description": "..." },
    "accepted": { "scheme": "exact", "network": "cardano:preprod", "asset": "lovelace",
                   "amount": "1500000", "payTo": "addr_test1...", "maxTimeoutSeconds": 600,
                   "extra": { "assetTransferMethod": "default" } },
    "payload": { "transaction": "<base64 CBOR signed tx>", "nonce": "<txHashHex>#<index>" }
  },
  "paymentRequirements": { ...same shape as accepted, server-authoritative... }
}
```

Response — always HTTP 200 for a *processed* verification (logical failures are
data, not transport errors):

```json
{ "isValid": true,  "payer": "addr_test1..." }
{ "isValid": false, "invalidReason": "invalid_exact_cardano_payload_ttl_expired",
  "invalidMessage": "optional human-readable detail" }
```

`invalidMessage` is a **non-normative additive field** (not in core v2; all
x402 SDK parsers are unknown-field-lenient, so it is safe on the wire) —
documented as such, never load-bearing.

HTTP 400 for malformed JSON / missing required fields; HTTP 500 only for
internal faults (sanitized body — see §13). Field names follow the demo's
`ProtocolJson` (Jackson `NON_NULL`, unknown-fields-lenient); wire-format
conformance is pinned by cross-implementation fixtures (§15), and known
core-v2 vs Cardano-reference conflicts are resolved explicitly in §5.2/§16.3
rather than papered over.

### 5.2 `POST /settle`

Request: identical shape to `/verify`. Response (`SettleResponse`):

```json
{ "success": true, "transaction": "<txHashHex>", "network": "cardano:preprod",
  "payer": "addr_test1...", "extra": { "status": "confirmed" } }
{ "success": false, "errorReason": "exact_cardano_settlement_not_confirmed",
  "transaction": "<txHashHex>", "network": "cardano:preprod" }
```

Contract invariants (Cardano-reference parity, kept from demo): `transaction`
and `network` are always non-null (`""` when no submission happened; the tx
hash whenever the tx **was** submitted, even on failure). `extra.status` is
**evidence-based**: `confirmed` only after observed block inclusion at the
configured depth; `mempool` only in the immediately-after-accepted-submission
case with `accept-mempool=true` (§7); on a confirmation timeout the field is
**omitted** — the facilitator has no proof the tx is still in any mempool, so
it asserts nothing.

Known conflict, resolved deliberately: core v2 prose wants an empty
`transaction` on failure, the Cardano reference returns the hash when a
submission happened (`failWithTx`). We follow the Cardano reference — the
hash is precisely what an operator needs to investigate a
`settlement_not_confirmed` — and flag the conflict upstream (§16.3).

### 5.3 `GET /supported`

```json
{
  "kinds": [
    { "x402Version": 2, "scheme": "exact", "network": "cardano:mainnet" },
    { "x402Version": 2, "scheme": "exact", "network": "cardano:preprod" }
  ],
  "signers": { "cardano:*": [] },
  "extensions": []
}
```

(`signers` keys are CAIP-2 family patterns per core v2 — `"cardano:*"`, not a
bare `"cardano"`.)

Only canonical network ids are advertised (never CIP-34 aliases). `extensions`
is truthful: empty until an extension from §16 is actually implemented.
`signers` stays empty (we hold no keys); the scheme spec permits an
observability address, which we omit.

### 5.4 Health

- `/actuator/health/liveness` — process up.
- `/actuator/health/readiness` — DB reachable **and** every **required**
  network's chain backend healthy. With per-network backends (§4.2/§10),
  aggregation is explicit: each network entry carries `required: true`
  (default); readiness = DB ∧ all required networks healthy. A degraded
  `required: false` network surfaces in `/health` detail and metrics but
  does not pull the instance out of rotation for its healthy networks
  (requests for the degraded network still fail closed per-request with
  `CHAIN_LOOKUP_FAILED`).
  Per-backend health: yaci-store (owns *all* mandatory capabilities for its
  network) → **slot-clock freshness** (expected current slot derived
  from wall clock and the network's slot config, minus the indexed tip slot,
  ≤ `x402.chain.tip-freshness-slots`, default 90) and store sync out of
  catch-up state — event recency is explicitly *not* the signal, because a
  catching-up index emits a continuous stream of old blocks and looks
  "fresh" while lagging hours behind — **and** submission connectivity
  (full variant: periodic N2C local-client probe against the node socket;
  light variant: established N2N connection to the relay — a fresh index
  with a broken submission path must not report ready) **and**
  protocol-params availability (current epoch's `EpochParam` present);
  blockfrost → last probe (cached ≤ 30 s) succeeded. An exhausted/invalid
  Blockfrost key must flip that network's health (demo gap: `/health`
  ignored Blockfrost entirely).
- `GET /health` — small JSON summary for humans (status, network(s), tip slot,
  sync lag, backend mode), kept from the demo.

## 6. Verification pipeline

One ordered pipeline; first failure wins and returns its error code. Codes are
the TS reference's catalogue (`constants.ts:220-282`), which the demo already
ports verbatim — cross-SDK stability of these strings is a compatibility
requirement.

**Stage A — envelope (no I/O)**

| # | Check | Error code |
|---|---|---|
| A1 | `x402Version == 2` (request + payload) | `invalid_exact_cardano_payload_unsupported_version` |
| A2 | scheme is `exact` and registered | `unsupported_scheme` |
| A3 | `accepted.network` ≡ `requirements.network` after CIP-34 normalization; network supported | `network_mismatch` |
| A4 | payload fields present/well-formed (`transaction` base64 decodes to ≤ `x402.verification.max-tx-bytes` — a **static DoS bound** (default 32 KiB), deliberately I/O-free; the live protocol `maxTxSize` is enforced later as C3; `nonce` matches `^[0-9a-f]{64}#\d+$`) | `invalid_exact_cardano_payload` / `..._nonce_invalid` |
| A5 | `requirements.amount` is a positive integer string; `asset` matches `lovelace` or `policy.assetNameHex` regex; `payTo` bech32-parses **and its network tag matches `network`** (audit M2 + M4 — new vs demo) | `invalid_exact_cardano_payload` |

**Stage B — transaction decode (no I/O)** — `CardanoTransactionDecoder`, kept
byte-for-byte compatible with the demo's proven port: tx hash = blake2b-256
over the **raw wire body bytes** (never re-serialized); `ttl`/`validityStart`
presence detected by raw-CBOR key inspection (cardano-client-lib models absent
as `0`); Ed25519 verification of every **vkey** witness against the body hash
(bootstrap witnesses are counted, not verified — B4).

| # | Check | Error code |
|---|---|---|
| B1 | CBOR deserializes | `..._transaction_decode_failed` |
| B2 | tx `networkId` (if present) matches the declared network | `..._network_id_mismatch` |
| B3 | ≥ 1 vkey, bootstrap, **or script** witness (TS/demo parity — both fold bootstrap into their vkey count and test the vkey∪bootstrap∪script union; a pure script-spend carries no vkey witnesses) | `..._unsigned` |
| B4 | every **vkey** witness Ed25519-verifies against the body hash; bootstrap (Byron) witnesses are counted toward B3 but **not** cryptographically verified (TS-reference parity — it counts, never verifies them); the decoder also exposes the set of verified witness **key hashes** for D5 | `..._invalid_signature` |

**Stage C — time & protocol limits (chain: current slot, protocol params)**

| # | Check | Error code |
|---|---|---|
| C1 | `ttl` (if present) **> current slot** — `ttl ≤ currentSlot` is expired (strict boundary, TS + demo parity) | `..._ttl_expired` |
| C2 | `validityStart` (if present) ≤ current slot | `..._not_yet_valid` |
| C3 | serialized tx size ≤ live protocol `maxTxSize` (effective cap = `min(x402.verification.max-tx-bytes, protocol maxTxSize)`) | `invalid_exact_cardano_payload` |

The current slot comes from the chain SPI, whose yaci implementation is
**fail-closed against staleness** (§9.1/§9.3): a lagging index must throw
(→ `CHAIN_LOOKUP_FAILED`), never return a low slot that would let an
actually-expired TTL pass C1.

**Stage D — replay protection (chain: UTxO set)** — the Cardano scheme's core
security property: the nonce is a *consumed UTxO*, and the ledger's own
no-double-spend rule is the durable, cross-instance replay guard.

| # | Check | Error code |
|---|---|---|
| D1 | `nonce` outref ∈ tx inputs | `..._nonce_not_in_inputs` |
| D2 | nonce UTxO present in the **live UTxO set** (absent = spent *or* never existed — reference semantics) | `..._nonce_not_on_chain` |
| D3 | every *other* input currently unspent | `..._input_not_available` |
| D4 | `payer` := owning address of the nonce UTxO (output of this stage, returned in responses) | — |
| D5 | **payer authorization** (closes audit L2, which TS/demo leave open): if the payer's payment credential is a **key** credential, its key hash must appear among the B4-verified vkey witness key hashes — otherwise `/verify` approves a tx the payer never authorized and that cannot phase-1-validate at submit. If it is a **script** credential (tolerated by TS/demo, so not rejected here), the tx must carry ≥ 1 script witness — the ledger, not the facilitator, validates script authorization at phase-2 (slightly stricter than TS, which checks nothing in this case; deliberate, fail-closed on obviously-unauthorizable txs). **Byron (bootstrap-address) payers are rejected** — bootstrap witnesses are not cryptographically verified (B4), so their authorization cannot be established; a documented deviation of negligible practical impact. Masumi is unaffected: it independently requires key-credential buyers (M3). | `invalid_exact_cardano_payload_payer_not_witness`* |

Chain lookups that *error* (backend down, rate-limited, UNKNOWN state in
yaci-store mode per §9.3) → `exact_cardano_facilitator_chain_lookup_failed`
(retryable; never an approval).

**Stage E — transfer-method verification** — dispatch on
`requirements.extra.assetTransferMethod` (default `"default"`) to a
`TransferMethodVerifier` Spring bean (pluggable; demo hardcoded the list):

*E-default* (`default`):

| # | Check | Error code |
|---|---|---|
| E1 | ≥ 1 output pays `payTo` | `..._recipient_mismatch` |
| E2 | that output carries the exact declared `asset` (policy+name exact; `lovelace` = coin field) | `..._asset_mismatch` |
| E3 | asset value ≥ `amount` (overpayment accepted) | `..._amount_insufficient` |
| E4 | output lovelace ≥ minUTxO = `(160 + serializedOutputSize) × coinsPerUtxoByte` (live protocol param) | `..._min_utxo_insufficient` |

*E-masumi* (`masumi`) — implements the scheme spec's Masumi rules in full, with
the TS reference (`exact/masumi/verify.ts`, audit-verified byte-level-correct)
as the porting source. **Scoping note:** the Java demo's `MasumiTransferVerifier`
covers only a subset (4 of the 9 Masumi error codes; no reference-script,
collateral-bounds, deadline, post-result-floor, or return-address checks) —
rows M2 (second half), M4's required-field presence validation, and M5–M9
are new work, not ports (reflected in milestone M5, §19).

| # | Check | Error code |
|---|---|---|
| M1 | `payTo` == expected escrow address; expected address comes from `extra.contractAddress` (**required** — a deliberate, security-motivated deviation from the current scheme text, which still allows defaulting to a canonical address; the audit's M1 fix recommends amending the spec in this direction, and the defaulting path is what caused C1 — deviation called out in §16.3) **validated against a configured per-network script-hash allowlist** (`x402.masumi.allowed-script-hashes`; ships with the verified v2 `vested_pay` hash `a15ce9d8…3b14ad`, NOT the stale `2025f0de…` — audit C1). No canonical preview deployment exists → preview requires explicit config. | `..._masumi_contract_mismatch` |
| M2 | escrow output has an **inline datum**, and **no reference script** | `..._masumi_datum_missing` / `..._masumi_reference_script` |
| M3 | datum structurally valid: `Constr 0`, 19 fields, `state == FundsLocked`, empty `result_hash`, cooldowns `0`, buyer/seller are key-credential addresses, `reference_signature` ≥ 16 bytes, time ordering `pay_by_time ≤ submit_result_time ≤ unlock_time ≤ external_dispute_unlock_time` (structural CBOR comparison, never hex-string equality — Evolution SDK emits indefinite-length CBOR) | `..._masumi_datum_invalid` |
| M4 | **`extra` schema validation first**: the Masumi `extra` fields the scheme marks required MUST be present in `requirements.extra` — the machine-checkable source of the required/optional split is the TS `CardanoExtraMasumi` interface (`types.ts:50-122`, `?` markers), cross-read with the scheme prose (`scheme_exact_cardano.md:144`, 211–219); missing required field → reject. This presence validation is **new work** (both TS and demo silently skip absent fields — "declared-then-compare"), scoped into milestone M5. Then datum fields match: buyer == payer credentials, seller == `extra.sellerAddress` credentials, and **every bound datum field** compared: declared values exactly; fields with scheme-specified defaults compared against the default when omitted (`inputHash` → empty, `collateralReturnLovelace` → 0 — an omitted field must not let a non-default datum value escape comparison); genuinely optional address fields per M5's presence/absence semantics | `invalid_exact_cardano_payload` / `..._masumi_datum_mismatch` |
| M5 | `buyer_return_address` / `seller_return_address`: a value declared in `extra` MUST be present in the datum with matching credentials (structural comparison); one omitted from `extra` MUST be `None` in the datum (scheme spec MUST; TS `returnAddressMatches`) | `..._masumi_datum_mismatch` |
| M6 | lovelace ≥ `amount + collateral_return_lovelace` (overpay allowed); native token == `amount` **exactly**; output carries exactly the requested asset set | `..._masumi_asset` / `..._amount_insufficient` |
| M7 | `collateral_return_lovelace` == 0 or ≥ 1,435,230 and ≤ locked lovelace | `..._masumi_collateral` |
| M8 | tx TTL, **converted from slot to POSIX milliseconds** via the network's slot config (per-era slot length + known zero point; a dedicated `NetworkClock` service owns slot↔time conversion, also used by §9.3 freshness), must be ≤ `pay_by_time`; TTL must be present for Masumi locks (an unbounded validity cannot satisfy the rule) | `..._masumi_deadline` |
| M9 | output lovelace ≥ **post-`SubmitResult`** min-UTxO floor (datum grown by 32-byte result hash + non-zero cooldowns) | `..._masumi_min_utxo` |

*E-script* (`script`) — **new vs demo** (TS implements it; requirement: full
verification parity):

| # | Check | Error code |
|---|---|---|
| S1 | Expected script address derived from `extra.scriptHash`, or from `extra.script` — including **applying `extra.parameters` to parameterized scripts** (scheme MUST; TS `scriptAddress.ts` does this): in Java via `aiken-java-binding`'s apply-params, with S1 conformance vectors extracted from the TS test suite so both implementations derive identical addresses. In scope from the start — a facilitator claiming the `script` method without it fails a MUST. Derived address must equal `payTo`. **Parameter ordering: wire compatibility wins** — the TS reference enumerates `Object.values(parameters)` (JS semantics: integer-like keys first, in numeric order — audit I2's quirk, still unfixed upstream), and deriving a *different* address than the reference would break every cross-implementation `payTo`; we therefore replicate the reference's observed enumeration order exactly, pin it with the conformance vectors, and propose the real fix upstream (define an explicit ordered parameter form — §16.3). Two implementation preconditions are flagged for M5, not assumed: confirm `aiken-java-binding` apply-params is equivalent to the TS `applyParamsToScript` for raw flat-encoded UPLC (not only Aiken-blueprint inputs), and lock the enumeration-order shim with adversarial vectors (integer-like keys). | `..._script_address_mismatch` |
| S2 | Asset/amount/min-UTxO checks as E2–E4 | as E-default |
| S3 | Datum policy, **Plutus-version- and datum-kind-aware** (full C2 remediation): **PlutusV1** — output MUST carry a **datum hash** (V1 predates inline datums; an inline-only or datum-less V1 output is unspendable → rejected). **PlutusV2** — output MUST carry an inline datum or datum hash; datum-less rejected. **PlutusV3** — datum-less allowed only under `x402.verification.script-datum-policy: v3-optional` (default `strict` rejects). **`scriptHash`-only requirements** (script language unknowable) — output MUST carry *some* datum (inline or hash); spendability beyond that cannot be established and is documented as the server's residual responsibility. No global off switch exists. Datum *contents* are never validated (spec: contract-specific). | `invalid_exact_cardano_payload_script_datum_missing`* |

\* New error code; additive, prefix-consistent. Flagged for upstreaming (§16.3).

Unknown `assetTransferMethod` → `unsupported_scheme` (demo parity).

Any uncaught exception in the pipeline → `invalid_exact_cardano_payload_verification_error`
(fail-closed), with the detail logged server-side only.

**Deliberate non-checks** (documented so reviewers don't mistake them for
gaps): tx *balance* (inputs ≥ outputs + fee) is left to node-side phase-1
validation at submit — the facilitator confirms inputs exist and outputs pay
the right party, which is what the x402 trust model needs; script execution
(phase-2) is likewise the node's job. An unbalanced tx passes `/verify` but
fails `/settle` with `exact_cardano_settlement_failed` — same behavior as TS
reference and demo.

## 7. Settlement pipeline

Order matters — the journal is consulted **before** re-verification, because a
successfully settled transaction has a *spent* nonce and can never re-verify
(a naive verify-first replay path would be unreachable).

1. **Decode + journal lookup** — decode the tx (Stage B; no chain I/O), compute
   the canonical tx hash, and compute the **settlement digest**: SHA-256 over a
   canonical-JSON serialization of `paymentRequirements` **plus the resource
   identity** (`paymentPayload.resource` URL). Requirements alone are not
   enough — two identically-priced resources would share a digest and one
   payment could be replayed across both; the resource binding makes each
   logical purchase distinct. Look up the journal row:
   - `CONFIRMED` **and digest matches** → **default behavior is
     `duplicate_settlement` (TS parity)**. *Idempotent replay* is **opt-in**
     (`x402.settle.idempotent-replay: false` default): a resource URL
     identifies an *endpoint*, not an individual *purchase* — under per-call
     pricing, a client re-presenting the same settled tx for a second call to
     the same URL would harvest the recorded success and get the second call
     free. Until the `payment-identifier` extension (§16.4) supplies a true
     per-purchase identity to bind the digest to, replay is only safe for
     deployments whose resource semantics make (tx, requirements, resource)
     unique — an explicit operator decision. When enabled, replay is **not**
     check-free: it re-runs the I/O-free mandatory profile — Stages A and B
     in full, **D1 plus equality of the incoming `payload.nonce` against the
     journaled `nonce_outref`** (D1 is structural, not chain-dependent — a
     tampered nonce must not replay), and D5 against the journaled `payer` —
     so a malformed or tampered request can never harvest a recorded
     success; only the live-chain checks (C, D2–D4) are skipped, because the
     recorded settlement already proved them at settle time and the nonce is
     now legitimately spent by this very tx. Then the **stability
     re-check**: if
     `confirmed_at` is younger than `x402.settle.stability-window` (default
     10 min), a one-shot, non-blocking `checkInclusion` must still see the tx
     on the best chain **at depth ≥ `confirmation-depth`**; on failure the
     row is demoted (fenced CAS → `SUBMITTED`) and the response is
     `success:false` / `exact_cardano_settlement_not_confirmed` with the tx
     hash — truthful: submitted, currently not confirmed; the reconciler
     re-promotes if/when it re-lands. Rows older than the window replay from
     the record directly.
   - `CONFIRMED` but **digest differs** → a *different* purchase trying to
     reuse a settled tx (cross-resource replay attempt): fall through to
     re-verification, which fails naturally on the spent nonce
     (`nonce_not_on_chain`).
   - `CLAIMED` (live) → `duplicate_settlement` (another attempt is in its
     claim→submit window).
   - `SUBMITTING` / `SUBMITTED` / `NOT_CONFIRMED` → one-shot, non-blocking
     `checkInclusion` (never a full `awaitInclusion` — duplicates must not
     hold threads or hammer the backend): included **at depth ≥
     `confirmation-depth`** → promote row, then treat as the `CONFIRMED`
     case above; not (yet) sufficiently confirmed → `duplicate_settlement`
     (demo/TS parity).
   - `EXPIRED` → one-shot `checkInclusion` first (a TTL-less tx marked
     expired by horizon could in principle still have landed): included **at
     depth ≥ `confirmation-depth`** (the same invariant as every other
     promotion path) → promote, then follow the `CONFIRMED` branch; else
     proceed (re-verification then fails on whatever the chain actually
     says).
   - `FAILED` / no row → proceed.

   **Lookup errors are never absence**: every one-shot `checkInclusion` in
   this step (and in the reconciler) distinguishes `NOT_SEEN` from a failed
   lookup — the SPI throws on timeout/rate-limit/outage, and the caller then
   preserves the row's state untouched and returns a retryable failure
   (`exact_cardano_facilitator_chain_lookup_failed`); no demotion, expiry, or
   promotion decision is ever taken on an errored lookup.
2. **Re-verify** — full §6 pipeline. Failure →
   `SettleResponse.fail(invalidReason)`; nothing submitted.
3. **Claim** — atomic claim on the canonical **tx hash** in Postgres (§8)
   with a fresh `attempt_id` (fencing token): `INSERT … ON CONFLICT` /
   guarded `UPDATE`. Conflict with a live claim → `duplicate_settlement`.
   Keying on the tx hash instead of the demo's base64 string is deliberate:
   it is the ledger's transaction identity (blake2b-256 of the body bytes) —
   the same identity the node dedupes on — immune to base64/padding variants
   (note: witness-set variants share it, which is *correct* for dedup: same
   payment).
4. **Submit** — transition `CLAIMED → SUBMITTING` (persisted, fenced on
   `attempt_id`) **before** the I/O, then `chain.submitTransaction(bytes)`.
   Outcomes are **classified** (SPI, §9.1):
   - *definitive rejection* (node/provider validation error — available in
     blockfrost mode and yaci-store **full**; the light variant's N2N path
     carries no verdict, so this outcome never occurs there, §9.3) →
     `FAILED` (claim released for legitimate retry), return
     `exact_cardano_settlement_failed` with the sanitized cause chain;
   - *accepted* → `SUBMITTED` (persisted **before** any confirmation
     polling begins — so TTL-based reclaim can never fire on an in-flight
     settlement);
   - *not submitted* (`NOT_SUBMITTED` — a local, pre-wire failure such as an
     unresolvable era or absent relay connection; nothing was broadcast) →
     `FAILED` (claim released), retryable `exact_cardano_settlement_failed`;
   - *unknown outcome* (timeout/disconnect — the node may have accepted) →
     row **stays `SUBMITTING`**, never auto-released; only the reconciler
     (§8) resolves it. Rebroadcast of the same tx is harmless (same hash),
     but reporting `FAILED` for a landed tx is not. **API response for this
     case** (defined so backends can't diverge): `success:false`,
     `errorReason: exact_cardano_settlement_not_confirmed`, `transaction` =
     the tx hash (derived pre-I/O), no `extra.status`; the caller's retry
     later hits the journal's `SUBMITTING` branch and converges on the truth.
5. **Await inclusion** — rollback-aware tracking up to
   `x402.settle.confirmation-timeout` (default 180 s). "Confirmed" =
   observed in a block on the current best chain at depth ≥
   `x402.settle.confirmation-depth` (default **1** = in a block, demo parity;
   operators may raise it — Praos finality is probabilistic and the scheme
   spec places mempool-grant liability on whoever accepts less). Confirmed →
   `CONFIRMED` (+ `response_json`, digest, slot/block recorded).
6. **Not confirmed in time** — row → `NOT_CONFIRMED` (kept — the tx may still
   land; retries must not rebroadcast blindly; audit L1: never release a
   claim after broadcast). Response: `success:false`,
   `exact_cardano_settlement_not_confirmed`, **no `extra.status` claim** —
   a timeout is not evidence the tx is in a mempool (the TS reference only
   reports `mempool` immediately after an accepted submission when
   confirmation-waiting is disabled). With `accept-mempool=true` the response
   is instead `success:true` + `extra.status:"mempool"` issued **immediately
   after accepted submission**, skipping the wait (TS-parity semantics) —
   available only where "accepted" carries a node verdict (blockfrost,
   yaci-store full); startup validation rejects it on the light variant
   (§9.3).
7. **Rollback reconciliation** — `CONFIRMED` is not immutable at shallow
   depth: (a) in yaci modes, the journal subscribes to rollback events and
   demotes `CONFIRMED` rows whose `confirmed_slot` lies beyond the rollback
   point back to `SUBMITTED` (the reconciler then re-resolves them); (b) the
   replay stability re-check in step 1 covers blockfrost mode and
   cross-instance gaps. Together these prevent the facilitator from replaying
   `success:true` for an orphaned transaction.

## 8. Persistence model (PostgreSQL)

Two Flyway-managed schemas in one database:

- **`store`** — yaci-store's own tables (its bundled migrations,
  `spring.flyway.locations: classpath:db/store/{vendor}`). Absent in
  blockfrost mode.
- **`facilitator`** — ours:

```sql
CREATE TABLE facilitator.settlement (
    tx_hash          text PRIMARY KEY,          -- canonical claim key (ledger tx id = body hash)
    attempt_id       uuid NOT NULL,             -- fencing token of the owning attempt
    requirements_digest text NOT NULL,          -- settlement digest: SHA-256 of canonical-JSON
                                                --   (PaymentRequirements + resource identity), §7.1
    network          text NOT NULL,
    status           text NOT NULL,             -- CLAIMED | SUBMITTING | SUBMITTED | NOT_CONFIRMED | CONFIRMED | FAILED | EXPIRED
    payer            text,
    pay_to           text,
    asset            text,
    amount           numeric,
    transfer_method  text,
    nonce_outref     text,
    tx_ttl_slot      bigint,                    -- lets the reconciler prove a tx can no longer land
    claimed_at       timestamptz NOT NULL DEFAULT now(),
    submitted_at     timestamptz,
    confirmed_at     timestamptz,
    confirmed_slot   bigint,
    confirmed_block  text,
    error_reason     text,
    response_json    jsonb                      -- recorded SettleResponse for idempotent replay
);
CREATE INDEX ON facilitator.settlement (status, claimed_at);
```

State machine (every transition is a **fenced compare-and-set**:
`UPDATE … WHERE tx_hash=? AND attempt_id=? AND status=?` — a suspended worker
resuming after its claim was reclaimed cannot overwrite the new attempt):

```
CLAIMED ──▶ SUBMITTING ──▶ SUBMITTED ──▶ CONFIRMED        (terminal-ish; rollback can demote)
   │            │              │  ▲            │
   │            │              ▼  │ (reconciler│ rollback event / stability
   │            │        NOT_CONFIRMED ────────┘  re-check demotes → SUBMITTED)
   │            │              │
   ▼            ▼              ▼
 FAILED   (stays; reconciler  EXPIRED  (tx TTL provably passed, not included)
 (definitive    only)
  reject)
```

Claim/reclaim semantics (replaces the demo's in-memory
`DuplicateSettlementCache`):

- Claim = `INSERT` with `status='CLAIMED'`, fresh `attempt_id`.
- TTL-based reclaim (`x402.duplicate-cache.ttl`, default 120 s) applies **only
  to `CLAIMED` rows** — i.e. attempts that died in the claim→submit window
  (seconds long, since `SUBMITTING` is persisted before I/O). It can never
  fire on an in-flight submission, so the old
  `duplicate-cache.ttl < confirmation-timeout` footgun is structurally gone
  (startup still sanity-warns on pathological values). `FAILED` and `EXPIRED`
  are immediately reclaimable. `SUBMITTING`/`SUBMITTED`/`NOT_CONFIRMED` are
  **never** time-reclaimed — only the reconciler resolves them.
- **Reconciler** (scheduled job, per instance with an advisory lock): sweeps
  `SUBMITTING`/`SUBMITTED`/`NOT_CONFIRMED` rows — one-shot inclusion check;
  included **at depth ≥ `confirmation-depth`** (the same bar as live
  settlement — promotion paths never accept a weaker standard than §7.5) →
  `CONFIRMED` (recording the response for replay); else if current slot >
  `tx_ttl_slot` + safety margin → `EXPIRED` (the tx can provably never land);
  else for **TTL-less transactions** (`tx_ttl_slot` null — nothing ever
  proves them dead) → `EXPIRED` after `x402.settle.reconcile-horizon`
  (default 24 h) without inclusion, with the §7.1 `EXPIRED`-branch re-check
  as the safety net should one land later against all odds; else leave for
  the next sweep. It also re-verifies recent `CONFIRMED` rows (younger than
  the stability window) against the best chain and demotes on rollback
  (§7.7). No zombie rows: every non-terminal state has an owner and a
  bounded lifetime.
- Multi-instance safe by construction (single-row atomicity + fencing),
  survives restarts, and doubles as the settlement audit log. The on-chain
  nonce spend remains the *durable* replay guard; this table closes the
  unconfirmed-window race the scheme spec describes (spec lines 480–499) and
  provides bounded-window rollback truthfulness on top.
- Retention: `CONFIRMED` rows are kept (audit value; archive job optional);
  `FAILED`/`EXPIRED` rows are pruned after a configurable horizon.

No Redis: Postgres already gives atomic claims at facilitator throughput
(hundreds of settles/s is far beyond a Cardano block's capacity anyway);
one less moving part in Compose.

## 9. Chain access layer

### 9.1 SPI

```java
public interface FacilitatorChainService {
    UtxoState getUtxoState(String txHash, int index);   // UNSPENT(owner addr) | SPENT | UNKNOWN
    long getCurrentSlot();                              // throws ChainLookupException when the
                                                        //   backing view is stale (fail-closed)
    SubmissionResult submitTransaction(byte[] txBytes); // era resolution is BACKEND-INTERNAL:
                                                        //   blockfrost needs none (era-agnostic HTTP CBOR);
                                                        //   the yaci backend resolves the current era per
                                                        //   submission (§9.3) for the N2C TxBodyType /
                                                        //   N2N era tag.
                                                        // ACCEPTED(txHash) | REJECTED(cause)
                                                        //   | UNKNOWN(cause) | NOT_SUBMITTED(cause)
                                                        //   — never a bare exception. NOT_SUBMITTED =
                                                        //   local failure BEFORE any wire I/O (era
                                                        //   unresolvable, relay connection absent):
                                                        //   nothing broadcast, so the claim is safely
                                                        //   released (SUBMITTING → FAILED) and the
                                                        //   response is a retryable settlement_failed.
                                                        //   Distinct from REJECTED (a node verdict,
                                                        //   unavailable on the light path) and UNKNOWN
                                                        //   (possibly broadcast — never released).
    InclusionResult checkInclusion(String txHash);      // one-shot: NOT_SEEN | INCLUDED(depth, slot, block);
                                                        //   throws on lookup failure — an errored lookup
                                                        //   is never reported as absence (§7.1)
    InclusionResult awaitInclusion(String txHash, int minDepth, Duration timeout);
                                                        // transient lookup errors inside the wait are
                                                        //   retried until the timeout; persistent failure
                                                        //   = not-confirmed-in-time (NOT_CONFIRMED path,
                                                        //   state preserved) — never demotion or release
    BackendHealth health();
}
public interface ProtocolParamsProvider {
    ProtocolParams current();                            // coinsPerUtxoByte, maxTxSize, ...
}
public interface NetworkClock {                          // per-network slot config (era-aware)
    long expectedSlotAt(Instant wallClock);              //   drives §9.3 freshness + M8 conversion
    Instant slotToTime(long slot);
}
```

Changes vs demo: tri-state `UtxoState` (was `Optional<UtxoInfo>` conflating
"never existed" with "spent" with "not in my index"); **classified submission
outcomes** — a transport timeout after the node may have accepted the tx is
`UNKNOWN`, not a failure, and drives the §7.4/§8 reconciliation path (the demo
collapsed everything into one exception and released the claim, risking a
`FAILED` verdict for a landed payment); a **one-shot `checkInclusion`** for
duplicate-settle probes and the reconciler (never blocks); `getCurrentSlot()`
is fail-closed by contract; explicit `health()`; protocol params split out and
**cached with refresh** (epoch-boundary or 15-min TTL — demo cached
`coinsPerUtxoByte` forever, missing governance changes; `maxTxSize` feeds C3);
`NetworkClock` owns slot↔time conversion.

### 9.2 Blockfrost backend

- `getUtxoState`: the demo's proven two-step — resolve the outref's owning
  address via cardano-client-lib `UtxoService.getTxOutput(txHash, index)`
  (404 → never existed), then scan that address's live UTxO set
  (`/addresses/{addr}/utxos`, paginated) for the outref (absent → spent).
  Known cost: busy addresses paginate; mitigated by short-TTL negative
  caching, and avoided entirely only by yaci-store mode for heavy traffic.
- Submission: `POST /tx/submit`, `application/cbor` body (verified against
  blockfrost-openapi).
- Inclusion: poll `GET /txs/{hash}` (block height) + latest block height for
  depth (the tx object itself carries no confirmation count — verified).
- Rate-limit awareness: 429/402 map to `CHAIN_LOOKUP_FAILED` (verify) /
  `SETTLEMENT_FAILED` (submit), with Micrometer counters.

### 9.3 yaci-store backend

Embedded via starters (all verified on Maven Central @ 2.0.2):
`yaci-store-spring-boot-starter` (core sync), `-blocks-`, `-utxo-`,
`-transaction-`, `-epoch-` (protocol params), `-submit-` (full variant);
Postgres datasource, schema `store`.

Two deployment **variants**, selected implicitly by configuration (an N2C
endpoint configured → **full**; none → **light**):

- **full** — syncs N2N from the operator's local `cardano-node` (bootstrapped
  via Mithril, §12) and submits N2C through its socket. The node fully
  validates the chain **from the bootstrap point onward**; the bootstrap
  itself trusts Mithril's threshold-multi-signed certified snapshot (a
  deliberately different, narrower trust anchor than a public relay's live
  view — and optional: operators wanting a literal zero-trust chain replay
  can disable `MITHRIL_SYNC` and sync from genesis, §12).
- **light** — no local node: syncs N2N from a configured **public relay** and
  submits via a facilitator-owned N2N adapter (yaci protocol stack — the
  stock client is unusable as-is, see below) to that relay. Two documented
  caveats: (1) **trust** — yaci-store is an indexer, not a validating node;
  the light variant trusts the configured relay's view of the chain for
  D2/D3 answers (choose IOG/CF-operated relays; use the full variant for
  production mainnet); (2) **no validation feedback** — the N2N
  tx-submission protocol carries no accept/reject verdict (the peer merely
  pulls announced txs), so `SubmissionResult.REJECTED` cannot occur on this
  path: outcomes are `ACCEPTED` (announced to the peer) or `UNKNOWN`, and a
  phase-invalid tx simply never appears in a block — the §8 journal absorbs
  this as `NOT_CONFIRMED`/`EXPIRED`. Because light-variant `ACCEPTED` is
  evidence-free, **`x402.settle.accept-mempool=true` is rejected at startup
  for a light-variant network** (fail-fast config validation, like §10's
  single-yaci-pipeline guard): "announced to a relay" must never convert
  into an immediate `success:true`/`status:"mempool"` — light always awaits
  real block inclusion. The stock yaci client **cannot be used as-is**:
  besides being javadoc-marked *"still under development"*, yaci 0.4.4's
  `TxSubmissionAgent` constructs its `ReplyTxIds`/`ReplyTxs` wire messages
  with the no-arg constructors that hardcode `Era.Babbage` and never
  propagates the request's `TxBodyType` (verified in source:
  `TxSubmissionAgent.getReplyTxIds()/getReplyTxs()`,
  `ReplyTxIds()/ReplyTxs()` → `Era.Babbage`) — a **Conway-era transaction
  would be era-mistagged on the wire and undecodable by the peer**. The
  light variant therefore ships its **own era-correct N2N submission
  adapter** (thin replacement for the agent/messages that tags the
  transaction's actual era), with a Conway-era devnet submission
  conformance test as an M4 exit criterion, and the fix is contributed
  upstream to bloxbean/yaci.

- **Indexing**: N2N chain-sync from `store.cardano.host:port` (local node in
  full, public relay in light). `ChainTipTracker` (BlockHeaderEvent) and `TxInclusionTracker`
  (TransactionEvent + RollbackEvent, rollback removes entries past the
  rollback point) carried over from the demo, with two fixes: (a) **freshness
  is slot-clock-based** — staleness = `NetworkClock.expectedSlotAt(now) −
  indexedTipSlot`, not time-since-last-event, because a catching-up index
  emits a steady stream of historical blocks and would look "fresh" while
  hours behind (this staleness gates `getCurrentSlot()` fail-closed, §9.1, and
  readiness, §5.4, and an explicit catch-up state is exposed); (b) inclusion
  additionally computes depth from tip height and is backed by the
  `transaction` store so inclusion survives restarts (demo: in-memory map,
  50k cap). Rollback events additionally feed the settlement journal's
  demotion path (§7.7).
- **UTxO state**: the local `utxo` store answers by outref — and, like
  `getCurrentSlot()`, **fail-closed against staleness**: when the index is in
  catch-up or beyond the slot-clock freshness bound, `getUtxoState` throws
  (`CHAIN_LOOKUP_FAILED`) rather than answer D2/D3 from a stale view —
  otherwise a no-TTL transaction could verify against a nonce that is
  already spent at the real tip. **Sync-start caveat (the honest
  trade-off):** an index started at slot S knows nothing about outrefs
  created before S → `UNKNOWN`. Policy, explicit config
  (`x402.chain.utxo-unknown-policy`) so nobody gets this by surprise:
  - `fail` (default): `UNKNOWN` → retryable `CHAIN_LOOKUP_FAILED`, message
    names the sync horizon. Right for operators who intend to sync from
    genesis/snapshot for complete answers (in the full variant, Mithril
    (§12) makes this cheap: the node restores a certified snapshot, and
    yaci-store indexes from a local, already-synced source; light-variant
    genesis sync from a public relay is slower and disk-heavy).
  - `reject-stale`: `UNKNOWN` → deterministic verification failure with the
    on-chain verdict codes (`..._nonce_not_on_chain` for the nonce,
    `..._input_not_available` for other inputs) and an `invalidMessage`
    naming the sync horizon — the client's correct action is exactly the
    same as for a truly-missing UTxO: build the payment from a recent UTxO.
    Right for operators who deliberately run a shallow index and accept the
    documented constraint that payments must spend recent UTxOs.
- **Submission** (the era both paths put on the wire — N2C `TxBodyType`,
  N2N era tag — is resolved **inside this backend** per submission, from
  yaci-store's era store (verified: `components/core` `EraStorage`;
  `EpochParam` does *not* carry the era) — **tip-bounded, not
  `findCurrentEra()`'s max-ever-stored**: the greatest stored era whose
  start slot ≤ the current best-chain indexed tip, so a rollback across a
  hard-fork boundary (vanishingly rare — eras activate at k-finalized epoch
  boundaries — but cheap to get right) cannot leave submissions tagged with
  an orphaned era; overridden by `x402.chain.era-override` when set;
  unresolvable era → `NOT_SUBMITTED` before any wire I/O, §9.1; a cross-era
  rollback test accompanies the M4 Conway conformance tests. Conway
  submission is conformance-tested on **both** paths): **full variant** — `yaci-store-submit` → N2C
  `LocalTxSubmissionClient` via `store.cardano.n2c-node-socket-path`
  (compose-mounted socket) or `n2c-host` (socat/TCP); verified in yaci-store
  source: gated exactly on those properties; we call
  `TxSubmissionService.submitTx(TxBodyType, bytes)` directly (bean, passing
  the request's era), not its REST controller; full node validation
  verdicts → `REJECTED` classification available. **Light variant** — the
  facilitator-owned era-correct N2N adapter (above) to the relay; no verdict
  semantics as described above.
- **Protocol params**: from the **epoch store** —
  `yaci-store-epoch-spring-boot-starter` is part of the yaci-mode starter
  dependencies, and its
  `EpochParamService` serves the current `EpochParam` from the local index
  (verified in the yaci-store repo: `stores/epoch` domain +
  `stores-api/epoch-api` service + the starter's protocol-params
  auto-configuration), **freshness-gated like every other read** (stale/
  catch-up index → `CHAIN_LOOKUP_FAILED`, never stale C3/E4 inputs).
  (yaci-store additionally has a `LocalEpochParam` N2C path; the epoch store
  is the committed primary — no open decision remains here.)
- **Sync-from-tip**: keep the demo's pre-context `EnvironmentPostProcessor`
  mechanism (inject `sync-start-slot`/`blockhash`), but resolve the start
  point via an N2N tip query against the configured sync source (yaci
  `TipFinder` — local node in full, relay in light) instead of hardcoding
  Blockfrost; no Blockfrost dependency remains in yaci-store mode.

### 9.4 Mode comparison (documented for operators)

| | blockfrost | yaci-store light | yaci-store full |
|---|---|---|---|
| Cardano node needed | no | no (public relay) | **yes** (local, Mithril-bootstrapped) |
| Third-party dependency | Blockfrost (hard) | public relay (sync + submit, trusted view) | Mithril aggregator (bootstrap only, optional) |
| Startup | instant | seconds (sync-from-tip) or long genesis sync | Mithril snapshot restore (minutes–hours) + index catch-up |
| UTxO answers | full set | full from sync horizon | full from sync horizon (genesis-cheap via Mithril) |
| Submit validation verdict | yes (HTTP error) | **no** (N2N, fire-and-propagate) | yes (N2C) |
| Cost/limits | API quota | infra only | infra only |

## 10. Multi-network support

`X402FacilitatorRegistry` keyed `(scheme, normalizedNetwork)` (demo design
kept, including exact-match normalization — no case folding). Networks come
from config:

```yaml
x402:
  networks:
    - id: cardano:preprod
      chain: { mode: yaci-store }   # light or full — full when an N2C endpoint is configured
    - id: cardano:mainnet
      chain: { mode: blockfrost, blockfrost: { ... } }
```

One `ExactCardanoScheme` instance per entry (fixes demo's single hardcoded
registration). At most one entry may use `yaci-store` (single
embedded sync pipeline); startup validation rejects violations with a clear
message.

## 11. Configuration reference

| Property | Default | Purpose |
|---|---|---|
| `x402.networks[]` (each with `chain.mode: blockfrost \| yaci-store`, backend config) | one preprod/blockfrost entry | §10, §4.2 |
| `x402.chain.tip-freshness-slots` | `90` | slot-clock staleness bound → fail-closed reads + readiness |
| `x402.chain.utxo-unknown-policy` | `fail` | §9.3 yaci-store semantics |
| `x402.chain.era-override` | unset | §9.3: explicit era wins over the tip-bounded era-store resolution for yaci submission paths (hard-fork transitions) |
| `x402.http.max-request-bytes` | `65536` | §13 pre-parse HTTP body cap (servlet filter) |
| `x402.verification.max-tx-bytes` | `32768` | A4 static DoS cap; effective limit is `min(cap, protocol maxTxSize)` via C3 |
| `x402.verification.script-datum-policy` | `strict` | S3 (V1/V2 rejection is unconditional; this only governs the V3 datum-less case) |
| `x402.masumi.allowed-script-hashes.<network>` | verified v2 hash (mainnet/preprod) | M1 / audit C1 |
| `x402.settle.confirmation-timeout` | `180s` | §7.5 |
| `x402.settle.confirmation-depth` | `1` | §7.5 |
| `x402.settle.poll-interval` | `3s` | inclusion poll |
| `x402.settle.accept-mempool` | `false` | §7.6; instance-global flag — startup fails fast when `true` while any configured network is yaci-store light (§9.3) |
| `x402.settle.idempotent-replay` | `false` | §7.1 (opt-in, digest-bound; default is TS-parity `duplicate_settlement`) |
| `x402.settle.stability-window` | `10m` | §7.1/§7.7 rollback re-check horizon |
| `x402.settle.reconcile-horizon` | `24h` | §8: TTL-less rows expire after this without inclusion |
| `x402.settle.max-concurrent` | `32` | bounded settlement concurrency (§13) |
| `x402.networks[].required` | `true` | §5.4 readiness aggregation |
| `x402.duplicate-cache.ttl` | `120s` | `CLAIMED`-only reclaim window (§8) |
| `x402.rate-limit.*` | on, generous | in-app limiter (§13) |
| `store.cardano.*` | — | yaci-store sync source (`host`/`port` — local node in full, public relay in light; the light variant's N2N submission targets the same relay) + N2C endpoint (full only; its presence selects the full variant) |
| `spring.datasource.*` | — | Postgres |

Secrets (`BLOCKFROST_PROJECT_ID`, DB password) via env only; never logged.

## 12. Docker Compose deployment

`deploy/docker-compose.yml`, two profiles:

- **`light`** — no local node: `facilitator` (Temurin-21 JRE image, non-root,
  read-only fs, healthcheck on readiness) + `postgres:17-alpine` (volume,
  healthcheck). The facilitator runs either **blockfrost** mode (`.env`
  carries the project id) or **yaci-store light** mode (N2N sync + N2N
  submission against a configured public relay — no API key, §9.3 trust
  caveats apply).
- **`full`** — adds two services for yaci-store full mode:
  - **`mithril-sync`** — one-shot init service modeled on
    cardano-rosetta-java's `docker/dockerfiles/mithril` (Dockerfile +
    entrypoint verified in that repo): runs `mithril-client cardano-db
    download latest --include-ancillary` into the shared node-DB volume,
    verifying the snapshot against the per-network Mithril **genesis and
    ancillary verification keys** (aggregator endpoints for mainnet/preprod/
    preview as in that entrypoint), so the node starts from a **certified
    trusted state** instead of syncing from genesis. Lifecycle semantics are
    hardened relative to the reference entrypoint (which always exits 0):
    on **first bootstrap** (no marker, no existing node DB) a failed
    download/verification **exits non-zero** — the
    `service_completed_successfully` gate must block, not let the node start
    on an empty DB and silently genesis-sync; partial downloads are staged
    and cleaned so a retry starts clean; the marker is written only after a
    verified restore. In **steady state** (marker present, DB exists) the
    reference behavior is kept: the existing — possibly ahead — node DB is
    preserved and the service exits 0 so restarts never wedge.
    `mithril-sync` is explicitly `restart: "no"` (a one-shot init job — the
    stack-wide `restart: unless-stopped` policy below does NOT apply to it,
    as in the rosetta-java compose). Toggleable via `MITHRIL_SYNC`
    (**default `true`** in the `full` profile — §9.4's startup
    characterization assumes it).
  - **`cardano-node`** (official `ghcr.io/intersectmbo/cardano-node` image;
    version pinned at implementation time) — `depends_on: mithril-sync:
    condition: service_completed_successfully`; shares the node-DB volume
    with `mithril-sync` and an `ipc` volume with the facilitator for the N2C
    socket; node healthcheck gates facilitator start.

Common (long-running services only — not `mithril-sync`):
`restart: unless-stopped`, log rotation, resource limits, a named
network, `depends_on: condition: service_healthy`. Images built with Spring
Boot's `bootBuildImage` or a two-stage Dockerfile (decided at implementation;
Dockerfile preferred for reproducibility). No Kubernetes manifests yet
(requirement says Compose "as of now"); nothing in the design blocks k8s later
(all state in Postgres, §3 non-functional).

## 13. Observability, security, hardening

Observability (all new vs demo):

- Micrometer → Prometheus: `x402_verify_total{outcome,reason,network}`,
  `x402_settle_total{...}`, latencies, chain-backend call counters + failures,
  `x402_sync_lag_slots`, duplicate-claim hits, protocol-params age.
- Logging via **Log4j2** (`spring-boot-starter-log4j2`, Spring's default
  Logback starter excluded project-wide; version managed by the Boot BOM):
  structured JSON output via the Log4j2 JSON template layout
  (profile-switchable, plain console in dev), correlation id per request via
  MDC/ThreadContext, never logging full transactions at INFO (hex-truncated
  at DEBUG). Note: yaci-store/cardano-client log through SLF4J, so they bind
  to Log4j2 transparently.
- Settlement journal (§8) as the reconciliation/audit surface.

Security & resource protection (a facilitator is an unauthenticated public
endpoint whose `/settle` legitimately takes minutes — exhaustion is the
realistic attack, not key theft):

- No keys, no custody (scheme guarantee — kept).
- **HTTP-layer request-size cap enforced before body parsing**
  (`x402.http.max-request-bytes`, default 65536 — a facilitator request is a
  few KiB of JSON), implemented as a servlet filter: fast-reject on `Content-Length` when present, and wrap the
  request input stream in a byte-counting bound that aborts with 413 the
  moment the limit is crossed — chunked/streamed bodies are therefore bounded
  too (there is no single portable Spring property for arbitrary JSON body
  size; the filter is the mechanism, verified by a pre-deserialization
  integration test with an oversized chunked body). Oversized bodies never
  reach Jackson, so A4 is the *second* line of defense, not the first.
  Jackson depth limits; base64 decoded size checked before CBOR.
- **Virtual threads** (Java 21, `spring.threads.virtual.enabled=true`) so a
  180 s confirmation wait does not pin a platform thread (implementation
  caveat: audit the JDBC pool and HTTP-client stacks for carrier-pinning
  `synchronized` blocks — HikariCP and cardano-client-lib versions matter),
  **plus** a bounded settlement gate: at most `x402.settle.max-concurrent`
  in-flight settlements (default 32); beyond it, fast `503 Retry-After` —
  never an unbounded queue.
- **Chain-provider call budgets and a shared bulkhead**: per-request caps on
  Blockfrost lookups (input count × pagination is the amplification vector —
  one `/verify` may otherwise fan out into dozens of paginated calls for a
  busy address) **and** a global per-provider concurrency bulkhead shared
  across all requests, so aggregate traffic cannot starve the provider quota
  either; budget/bulkhead exhaustion → `CHAIN_LOOKUP_FAILED` (fail closed).
- In-app rate limiter (bucket4j) **on by default** with generous per-IP
  limits; operators should still front with a reverse proxy / WAF for real
  ingress control (documented in `deploy/`), but the Compose-default
  deployment is not trivially exhaustible out of the box.
- Sanitized errors: 500 bodies carry a correlation id only (demo leaked
  exception messages); full detail in logs.
- Optional shared-secret auth (`X-Api-Key`) for `/settle` (some operators run
  facilitators semi-privately); off by default — the protocol assumes open
  facilitators.
- CORS closed by default (facilitator is server-to-server).

## 14. Audit-finding and demo-gap remediation matrix

| Finding / gap | Disposition in this design |
|---|---|
| Audit C1 (stale Masumi script hash) | Verified v2 hash shipped as config default + allowlist check M1; no hardcoded derivation; preview requires explicit config (§6 E-masumi) |
| Audit C2 (script datum stranding) | `script` method implemented with Plutus-version- and datum-kind-aware policy S3 (V1 → datum hash required; V2 → datum required; V3 → configurable; unknown → some datum required); no global off switch (§6 E-script) |
| Audit H1 (Masumi lovelace post-result floor) | Client-side concern; facilitator keeps enforcing the post-`SubmitResult` floor (M9) — documented so client SDKs size locks correctly |
| Audit H2 (no Cardano e2e in CI) | §15: devnet e2e job in this repo's CI |
| Audit M1 (`contractAddress` required vs optional) | Required + allowlisted (safe direction, matches audit recommendation) |
| Audit M2 (negative amount) | A5 positive-integer validation |
| Audit M4 (payTo network tag) | A5 bech32 network-tag check |
| Audit L1 (claim released on confirmation timeout) | §7.6: row kept as `NOT_CONFIRMED`, reconciler-owned; released only on definitive rejection or proven TTL expiry |
| Audit L2 (vacuous signature validity / no payer-key membership) | Closed by D5: payer's payment-key hash must be among the verified vkey witnesses (new check + error code; TS/demo leave this open) |
| Audit L6 (nonce contention `utxos[0]`) | Client-side; noted in integration docs |
| Audit I1/I10 + preprod-USDM policy-id discrepancy (spec doc `16a55b…` vs code `e675b4…`) | Facilitator is asset-agnostic (validates what requirements declare) — unaffected; discrepancies reported upstream (§16.3) |
| Demo: in-memory duplicate cache, single instance | Postgres claim table (§8) |
| Demo: H2 file DB | Postgres (§8) |
| Demo: single network hardcoded | Config-driven multi-network (§10) |
| Demo: `coinsPerUtxoByte` cached forever | Refreshing `ProtocolParamsProvider` (§9.1) |
| Demo: dead Blockfrost inclusion fallback | Whole class removed: no composite backend exists — one backend owns every capability per network, fail-closed (§4.2) |
| Demo: `/settle` re-verify doubles Blockfrost load | Accepted in blockfrost mode (correctness first; mitigated by §9.2's negative caching); yaci-store mode has no API-quota cost at all; optional short-TTL verify-result cache explicitly rejected to keep settle checks fresh |
| Demo: `/health` ignores Blockfrost; 90 s hardcoded | Readiness probes both backend and DB; freshness configurable (§5.4) |
| Demo: error message leakage; no metrics; no auth option; public-relay hard dependency | §13; §12 topology makes the node operator-controlled in full mode |
| Demo: verifier list hardcoded | Spring-injected `TransferMethodVerifier` beans (§6 Stage E) |
| Core-v2 vs Cardano-reference wire conflicts (failure `transaction` hash; `invalidMessage`; signers key shape) | Resolved explicitly: follow the Cardano reference for the failure hash, document `invalidMessage` as additive, use CAIP-2 family pattern `cardano:*` (§5, §16.3) |

## 15. Testing strategy

- **Unit**: port the demo's 69-test matrix (it covers essentially every error
  branch, the CBOR edge cases — `ttl=0` vs absent — the 8-thread claim race,
  Masumi datum negatives, rollback handling) into the new package layout;
  deterministic `TestTx` fixture builder kept. Add: script-method suite
  (incl. apply-params conformance vectors from the TS tests, per-version
  datum-policy negatives), payTo-network-tag negatives, amount-validation
  negatives, tri-state UTxO policy tests, D5 payer-witness-membership
  negatives, Masumi return-address and required-field negatives, TTL
  boundary (`ttl == currentSlot` rejected) and slot↔time conversion tests,
  slot-clock staleness (catch-up looks stale, not fresh), journal state
  machine (fenced transitions, `SUBMITTING` recovery, reclaim windows,
  digest-bound replay incl. cross-digest rejection, rollback demotion,
  reconciler promote/expire paths), light-variant submission semantics
  (N2N path never yields `REJECTED`; unknown outcomes resolve via the
  reconciler; `NOT_SUBMITTED` releases the claim), era resolution
  (tip-bounded selection incl. the cross-era rollback case; Conway-era
  submission encoding on both the N2C and N2N paths — §9.3/M4).
- **Integration**: Testcontainers Postgres (claim atomicity incl. two app
  contexts against one DB); WireMock Blockfrost backend; yaci-store event
  pipeline against recorded block fixtures.
- **E2E (CI, addresses audit H2)**: yaci-devkit devnet in a service container —
  full flow: build+sign tx (cardano-client-lib test client) → `/verify` →
  `/settle` → confirmed on devnet; plus preprod smoke test (manual/nightly,
  needs funded wallet + Blockfrost key).
- **Cross-implementation conformance**: replay the TS facilitator's test
  vectors (JSON fixtures) where extractable, pinning wire-format compatibility.

## 16. Improvements & discussion items

### 16.1 Adopted improvements (rationale in-line above)
Postgres claim journal + idempotent replay; tri-state UTxO SPI; confirmation
depth; protocol-params refresh; multi-network config; verifier SPI; script
method with datum policy; Masumi hash allowlist; real health/readiness;
metrics; sanitized errors; input caps; single-owner backend model (no
composite/failover dead code by construction).

### 16.2 Considered and rejected (for now)
- **Hybrid split-backend mode** (the demo's composite: yaci tip/inclusion +
  Blockfrost UTxO/submit) — removed by requirement. Early drafts of this
  spec carried it and the review loop repeatedly surfaced split-brain
  subtleties (who answers what, readiness aggregation, failover dead code);
  one backend owning every capability per network eliminates the class.
- **Redis** for claims — Postgres suffices, fewer parts.
- **Kafka/outbox eventing** — no consumer exists yet; journal table already
  holds the data if one appears.
- **Verify-result caching between `/verify` and `/settle`** — staleness risk
  on the replay-critical nonce check outweighs saved lookups.
- **v1 wire support** — Cardano scheme is v2-only across the ecosystem.

### 16.3 Upstream feedback (to x402 repo, not this codebase)
Preprod USDM policy-id mismatch between scheme doc and `constants.ts`;
README "six rules" vs seven; PAYMENT-RESPONSE prose vs schema (`transaction` +
`extra.status`); core-v2 "empty `transaction` on failure" vs the Cardano
reference's `failWithTx` hash (we follow the reference — needs upstream
resolution); proposals for two additive error codes,
`invalid_exact_cardano_payload_script_datum_missing` and
`invalid_exact_cardano_payload_payer_not_witness` (closes audit L2 at spec
level); amend the scheme to require `extra.contractAddress` for Masumi
(audit M1's recommendation — this design already enforces it, a documented
deviation from the current defaulting text); define an explicit **ordered**
`extra.parameters` form for the `script` method (audit I2 — the reference's
`Object.values` enumeration order is currently the de-facto wire contract,
which this design replicates for compatibility, §6 S1); DEFAULT_ASSETS.md
lacks the Cardano section its checklist implies.

### 16.4 Extensions (future)
`payment-identifier` (idempotency key pass-through — cheap, and the missing
piece that would make §7.1's idempotent replay safe to enable by default: it
supplies the per-purchase identity a resource URL cannot), `bazaar` discovery
indexing. Both additive; `/supported.extensions` stays truthful until
shipped.

## 17. Out of scope

Client/payer SDK, resource-server middleware, Masumi release/dispute,
fee sponsorship, non-`exact` schemes, key custody, GUI, Kubernetes manifests.

## 18. Resolved design decisions

| Decision | Choice | Why |
|---|---|---|
| Build tool / structure | single Gradle Spring Boot project, layered packages (`model`/`service`/`controller`/`chain`/`repository`/`config`) | owner requirement; Gradle for yaci-store/demo ecosystem; yaci-store deps dormant-on-classpath trade-off documented in §4.1 |
| Root package / group | `org.cardanofoundation.x402.facilitator` / `org.cardanofoundation` | owner requirement; app class at package root |
| Boot line | 3.5.x / Java 21 | yaci-store 2.0.2 compatibility (verified); 4.x when upstream moves |
| DTO style | Java records, Jackson, `NON_NULL`, lenient unknowns | demo-proven wire compatibility |
| Claim key | ledger tx id (blake2b-256 of body bytes) | the identity the node dedupes on; witness/encoding-variant-proof |
| Default mode | `blockfrost` on preprod | lightest onboarding; yaci-store full is the production-mainnet recommendation |
| Logging | Log4j2 (`spring-boot-starter-log4j2`, default Logback excluded) | user requirement; JSON template layout for structured logs |
| Light yaci-store submission | facilitator-owned era-correct N2N adapter (yaci protocol stack; stock client era-mistags, §9.3) | keeps light mode third-party-free; no-verdict semantics absorbed by the §8 journal |
| Spec location for the facilitator's own docs | this file + ADRs under `docs/` | repo founding document |

## 19. Milestones

1. **M1 — Skeleton & protocol**: project skeleton + §4.1 package layout
   under `org.cardanofoundation.x402.facilitator`, revived DTOs/constants,
   `/supported`, registry, CI (build + unit).
2. **M2 — Verification core**: decoder port, Stage A–E default method, unit
   matrix green, WireMock Blockfrost backend, `/verify` complete.
3. **M3 — Settlement & persistence**: Postgres journal/claims, `/settle`,
   Blockfrost submission + inclusion, Testcontainers suite.
4. **M4 — yaci-store backend**: stores + submit wiring (N2C full variant;
   N2N light variant with the **era-correct submission adapter** — yaci
   0.4.4's stock agent hardcodes `Era.Babbage`, §9.3 — plus the tip-bounded
   era resolution shared by both paths). Explicit exit criteria:
   **Conway-era devnet submission conformance tests on both paths** (N2C
   full and N2N light) and the **cross-era rollback test** for the
   tip-bounded era resolution (§9.3); upstream PR to bloxbean/yaci.
   Trackers, tri-state policies, compose `full` profile with Mithril
   bootstrap, devnet e2e.
5. **M5 — Masumi + script methods**: Masumi verifier — extend the demo's
   subset (4 of 9 error codes) to the full rule set M1–M9 (reference-script,
   collateral bounds, deadline conversion, post-result floor, return
   addresses are **new work**, ported from the TS reference); allowlist
   config; script method incl. apply-params derivation and per-version datum
   policy; cross-implementation conformance fixtures.
6. **M6 — Hardening**: metrics, health, security items (§13), docs, preprod
   smoke, mainnet readiness review.
