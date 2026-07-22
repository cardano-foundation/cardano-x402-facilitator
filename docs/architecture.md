# Architecture

A single Spring Boot service. Two things make the shape non-obvious and are worth
reading before changing anything: the **chain backend is one Blockfrost client
whose target is configurable** (hosted Blockfrost, or a standalone yaci-store
instance consumed over its Blockfrost-compatible API, chosen per network via
`base-url`), and **settlement is a journalled state machine**, not a
request-scoped operation.

## Package layout

```
org.cardanofoundation.x402.facilitator
├── FacilitatorApplication          Spring Boot entry point (package root)
├── controller/                     HTTP surface — /verify, /settle, /supported, /health
│   └── advice/                     Sanitized error surface
├── model/
│   ├── protocol/                   x402 wire DTOs (records) + ProtocolJson
│   ├── chain/                      Chain SPI value types (UtxoState, SubmissionResult, …)
│   ├── entity/                     SettlementRecord + Status
│   └── ErrorCodes                  The facilitator's error-code catalogue
├── service/
│   ├── registry/                   (version, scheme, network) -> handler dispatch
│   ├── verification/               ExactCardanoScheme (stages A–E)
│   │   ├── decoder/                CBOR -> DecodedTransaction
│   │   └── method/                 default | masumi | script verifiers
│   └── settlement/                 Service, gate, reconciler, digest
├── chain/                          Chain SPI + blockfrost/ impl (also serves yaci-store)
├── repository/                     SettlementRepository (JDBC, CAS transitions)
└── config/                         Properties, filters, wiring, startup validation
```

`model` / `service` / `controller` carry the required layering; `chain`,
`repository`, and `config` are additional packages for the SPI, persistence, and
wiring.

## Request flow

```
POST /verify or /settle
  → CorrelationIdFilter      HIGHEST_PRECEDENCE      X-Correlation-Id (generated if absent)
  → ApiGuardFilter           HIGHEST_PRECEDENCE+10   API key → 401, rate limit → 429  [opt-in]
  → RequestSizeFilter        (unordered ⇒ last)      byte cap → 413
  → FacilitatorController    null checks; registry lookup
  → X402FacilitatorRegistry  (2, "exact", normalize(network)) → handler
  → DefaultSchemeNetworkFacilitator
      ├── verify → ExactCardanoScheme      stages A–E
      └── settle → SettlementGate → SettlementService
```

Correlation runs first so every rejection downstream is traceable. `RequestSizeFilter`
declares **no** `@Order`, so it defaults to `LOWEST_PRECEDENCE` and runs last of
the three — meaning auth and rate limiting are applied *before* the body size cap.
That ordering is defensible (an unauthenticated caller is rejected without
touching the body), but it is a default rather than a decision: nothing pins it,
and adding `@Order` to another filter could silently reshuffle it.

The registry keys on `(scheme, normalized network)` and rejects any
`x402Version != 2` outright. An unregistered triple returns HTTP 500
(see [api.md](api.md)).

`DefaultSchemeNetworkFacilitator` delegates through injected `BiFunction`s rather
than depending on the verification and settlement services directly. That keeps
the dependency graph acyclic — settlement itself needs the verifier, which would
otherwise form a cycle.

## Chain access layer

Everything chain-facing sits behind an SPI, so verification and settlement never
know which backend they're on:

| Type | Purpose |
|---|---|
| `FacilitatorChainService` | UTxO state, current slot, submit, inclusion, health |
| `ProtocolParamsProvider` | min-UTxO coefficient, max tx size |
| `NetworkClock` | slot ↔ POSIX time (Masumi deadlines) |

### `UtxoState` is tri-state

`Unspent` | `Spent` | `Unknown`. The third is load-bearing: an indexer behind the
tip cannot tell "never existed" from "not yet indexed". Both collapses are real
bugs — into `Spent` rejects honest payments, into `Unspent` accepts replays. So
`Unknown` degrades to a retryable `chain_lookup_failed`. Same rule for
`SubmissionResult.Unknown`: a submission that *might* have been broadcast never
releases the claim.

### The chain backend

There is exactly one backend: the cardano-client-lib Blockfrost provider
(`BFBackendService`, wrapped in `BlockfrostChainService`). `ChainBackendFactory`
builds it from a single property per network, `chain.blockfrost.base-url` (env
`BLOCKFROST_BASE_URL`), plus `chain.blockfrost.project-id` (env
`BLOCKFROST_PROJECT_ID`). There is no "chain mode" concept — no Spring profile,
no alternate code path.

Because yaci-store exposes a Blockfrost-compatible API, the same client serves
a standalone yaci-store just as well as hosted Blockfrost — `base-url` just
points at a different host:

| | Hosted Blockfrost | Standalone yaci-store |
|---|---|---|
| `base-url` | Blockfrost's hosted API (default) | e.g. `http://yaci-store:8080/api/v1/blockfrost` |
| `project-id` | required | ignored |
| Infra | none beyond a project id | a `yaci-store` deployment (its own cardano-node sync + Postgres) |
| Submission | Blockfrost `tx/submit` | yaci-store's `/tx/submit`, forwarded to its node |

The facilitator does not embed an indexer, a JPA store, or any yaci-store
library code; when yaci-store is used it runs as its own service (see
[../deploy/README.md](../deploy/README.md)) and is addressed over HTTP,
identically to Blockfrost.

Because both targets share `BlockfrostChainService`, both inherit its
`404 → Spent` semantics (see "`UtxoState` is tri-state" above). Neither
resolution produces `UtxoState.Unknown` today — it remains part of the SPI
contract for a future backend that can distinguish "not yet indexed" from
"never existed", but nothing currently returns it. `SubmissionResult.Unknown`
is unaffected: a transport failure after the wire still returns it either way,
since the node may have accepted the submission regardless.

---

## Settlement

Settlement can't be request-scoped: a tx may land after the HTTP response, the
process may die mid-submit, and a confirmed block may roll back. So state is
journalled in Postgres and swept asynchronously.

### State machine

```
                 ┌──────────────────────────────────┐
   insert/reclaim│                                  │
        │        ▼                                  │
        └──▶ CLAIMED ──▶ SUBMITTING ──▶ SUBMITTED ──┼──▶ CONFIRMED
                             │              │       │        │
                             │              └──▶ NOT_CONFIRMED
                             │                     │       (rollback)
                             ▼                     │          │
                          FAILED              EXPIRED ◀───────┘
```

| Status | Meaning |
|---|---|
| `CLAIMED` | Journalled, nothing on the wire yet |
| `SUBMITTING` | About to hit the wire — persisted **before** the call |
| `SUBMITTED` | Node accepted it |
| `NOT_CONFIRMED` | Broadcast, confirmation timed out — **may still land** |
| `CONFIRMED` | Included at `confirmation-depth` |
| `FAILED` | Rejected or never broadcast |
| `EXPIRED` | Past TTL (+120 slot margin) or the reconcile horizon, never included |

Transitions:

| From → To | Trigger |
|---|---|
| → `CLAIMED` | Verification passed; insert, or reclaim a dead row |
| `CLAIMED` → `SUBMITTING` | Before any wire I/O |
| `SUBMITTING` → `FAILED` | `Rejected` or `NotSubmitted` |
| `SUBMITTING` → `SUBMITTED` | `Accepted` |
| `SUBMITTING` → *(stays)* | **`Unknown`** — may have been broadcast; reconciler only |
| `SUBMITTED` → `CONFIRMED` | `awaitInclusion` reached depth |
| `SUBMITTED` → `NOT_CONFIRMED` | Confirmation timed out |
| `SUBMITTING`/`SUBMITTED`/`NOT_CONFIRMED` → `CONFIRMED` | Repeat `/settle` or reconciler finds inclusion |
| `EXPIRED` → `CONFIRMED` | A late tx landed after all — recovered |
| `CONFIRMED` → `SUBMITTED` | **Rollback**: no longer included within the stability window |
| `…` → `EXPIRED` | Not included, past TTL+120 slots or the horizon |

`SUBMITTING → (stays)` on `Unknown` is the deliberate one. The node may have
accepted it, so marking `FAILED` risks a double-spend when the caller retries.
The row stays claimed and the reconciler resolves it from the chain.

`CONFIRMED → SUBMITTED` exists because confirmation isn't final: within the
stability window a confirmed tx can roll back, and reporting stale success would
grant a resource that was never paid for.

### Claiming and idempotency

Every transition is a **fenced CAS**: `UPDATE … WHERE tx_hash = ? AND attempt_id = ?
AND status = ?`. A reclaimed row's old attempt can never clobber the new one.

The claim is `INSERT` on the `tx_hash` primary key — the DB, not the application,
arbitrates the race. On conflict it tries `reclaim()`, which takes over rows that
are `FAILED`/`EXPIRED` (unconditionally) or `CLAIMED` past the claim TTL (a
worker that died between claim and submit). Both failing → `duplicate_settlement`.

`requirements_digest` is SHA-256 over `{requirements, resource.url}` with map
keys sorted. It identifies *the same logical request from the same producer* — it
is not fully canonical JSON, and cross-producer digest equality is not a goal.

Idempotent replay is **off by default** (`x402.settle.idempotent-replay`). When
on, a repeat of a `CONFIRMED` payment with a matching digest returns the cached
response — but never blindly: if the confirmation is inside the stability window
it re-checks the chain first, and demotes to `SUBMITTED` +
`settlement_not_confirmed` if inclusion no longer holds. A digest **mismatch**
falls through to a fresh verify, where the reused tx fails naturally on
`nonce_not_on_chain`.

### Reconciler

`@Scheduled(fixedDelay = 30s, initialDelay = 30s)` sweeps up to 200 rows that are
`SUBMITTING`/`SUBMITTED`/`NOT_CONFIRMED`, plus `CONFIRMED` rows still inside the
stability window (rollback watch).

On Postgres it takes a `pg_try_advisory_lock`, so **only one instance sweeps** —
horizontal scaling is safe. On other vendors (H2 in tests) it runs unlocked.

A `ChainLookupException` **skips** the row and preserves its state. Errors are
never treated as absence — that's what would turn a Blockfrost blip into a
wrongly-`EXPIRED` payment.

### Settlement gate

`POST /settle` returns **503** when the backend for that network is unhealthy,
rather than accepting a settlement it cannot confirm. Submit-then-confirm is
unreliable against a blind backend, so the honest answer is a retryable refusal.

---

## Persistence

One table, `facilitator.settlement`, keyed by `tx_hash`:

| Column | Type | Notes |
|---|---|---|
| `tx_hash` | `varchar(64)` | **PK** — the idempotency arbiter |
| `attempt_id` | `uuid` | NOT NULL — the CAS fence |
| `requirements_digest` | `varchar(64)` | NOT NULL — SHA-256 |
| `network`, `status` | `varchar(64)`, `varchar(16)` | NOT NULL |
| `payer`, `pay_to`, `asset`, `amount` | | nullable |
| `transfer_method`, `nonce_outref`, `tx_ttl_slot` | | nullable |
| `claimed_at` | `timestamptz` | NOT NULL |
| `submitted_at`, `confirmed_at`, `confirmed_slot`, `confirmed_block` | | nullable |
| `error_reason`, `response_json` | | nullable |

Index `idx_settlement_status_claimed (status, claimed_at)` serves the reconciler
sweep and staleness queries.

Migrations run programmatically (`spring.flyway.enabled: false`):
`FlywayConfig.facilitatorFlyway` applies `classpath:db/migration` into the
`facilitator` schema with its own history table. That's the only Flyway runner
in the facilitator process — yaci-store, when used, runs as a separate service
and migrates its own schema independently, so the two never share a history
table or interfere.

## Startup validation

Fails fast rather than surfacing misconfiguration as runtime errors:

- `x402.networks` non-empty
- every network id supported (`cardano:mainnet|preprod|preview`, CIP-34 aliases ok)
- every network declares `chain.blockfrost.base-url`

## Cross-cutting

| Concern | Mechanism |
|---|---|
| Logging | Log4j2 (`log4j2-spring.xml`), JSON layout available; `%X{correlationId}` |
| Metrics | Micrometer → `/actuator/prometheus` |
| Correlation | `X-Correlation-Id` generated/echoed/logged; `500` bodies carry it and nothing else |
| Concurrency | Virtual threads (`spring.threads.virtual.enabled: true`) |
| JSON bounds | `StreamReadConstraints` under the byte cap (defence in depth) |

## Related

- [api.md](api.md) — wire contract and error codes
- [verification.md](verification.md) — the A–E rules in detail
- [configuration.md](configuration.md) — every property
- [../deploy/README.md](../deploy/README.md) — deployment, Compose, mainnet checklist
