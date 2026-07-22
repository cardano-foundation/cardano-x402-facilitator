# Configuration Reference

All properties live under the `x402` prefix (`config/X402Properties`), set in
`application.yml` or overridden per environment. Standard Spring relaxed binding
applies, so `x402.settle.confirmation-depth` is
`X402_SETTLE_CONFIRMATIONDEPTH` as an environment variable.

Defaults below are the **code** defaults (the `…OrDefault()` accessors), which
apply when a key is absent entirely. `application.yml` ships explicit values for
most of them; where the shipped value differs from the code default, both are
listed.

## Chain backend

There is exactly one chain backend: the cardano-client-lib Blockfrost provider
(`BFBackendService`, wrapped in `BlockfrostChainService`). Configure it per
network with `chain.blockfrost.base-url` (env `BLOCKFROST_BASE_URL`) and
`chain.blockfrost.project-id` (env `BLOCKFROST_PROJECT_ID`) — that's the entire
surface, no "mode" property involved:

```bash
BLOCKFROST_PROJECT_ID=... java -jar facilitator.jar
BLOCKFROST_BASE_URL=http://localhost:8080/api/v1/blockfrost java -jar facilitator.jar
```

To run against a standalone yaci-store instead of hosted Blockfrost, point
`base-url` at its Blockfrost-compatible endpoint; `project-id` is required for
hosted Blockfrost and ignored by yaci-store. Same client, same code path
either way — see [architecture.md](architecture.md#the-chain-backend).
yaci-store's own sync/submission configuration (which node to follow, protocol
magic, N2C socket, sync-start intersect, pruning) lives on the yaci-store
service itself, not the facilitator; see
[../deploy/README.md](../deploy/README.md) for the Compose wiring.

## Server and datasource

| Key | Default | Notes |
|---|---|---|
| `server.port` | `4022` | |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/facilitator` | env `DB_URL` |
| `spring.datasource.username` | `facilitator` | env `DB_USER` |
| `spring.datasource.password` | `facilitator` | env `DB_PASSWORD` |
| `spring.flyway.enabled` | `false` | Deliberate — see below |
| `spring.threads.virtual.enabled` | `true` | |

`spring.flyway.enabled: false` is not an oversight. The facilitator schema is
migrated by a **programmatic** runner (`FlywayConfig`) into the `facilitator`
schema with its own history table — the only Flyway runner in the process now
that yaci-store, when used, runs as a separate service with its own schema and
migrations.

## Networks

At least one entry is required; startup fails otherwise.

```yaml
x402:
  networks:
    - id: "cardano:preprod"       # cardano:mainnet | cardano:preprod | cardano:preview
      required: true              # default true
      chain:
        blockfrost:
          # Hosted Blockfrost, OR a standalone yaci-store's Blockfrost-compatible
          # endpoint (e.g. http://yaci-store:8080/api/v1/blockfrost). project-id is
          # required for hosted Blockfrost and ignored by yaci-store.
          base-url: ${BLOCKFROST_BASE_URL:https://cardano-preprod.blockfrost.io/api/v0}
          project-id: ${BLOCKFROST_PROJECT_ID:}
      slot-config:                # optional; overrides the built-in anchor
        zero-slot: 86400
        zero-time-epoch-seconds: 1655769600
        slot-length-ms: 1000
```

Built-in anchors (`ShelleyNetworkClock`), used when `slot-config` is absent —
override only if you have a reason:

| Network | `zero-slot` | `zero-time-epoch-seconds` | `slot-length-ms` |
|---|---|---|---|
| `cardano:mainnet` | `4492800` | `1596059091` | `1000` |
| `cardano:preprod` | `86400` | `1655769600` | `1000` |
| `cardano:preview` | `0` | `1666656000` | `1000` |

**An override needs both `zero-slot` and `zero-time-epoch-seconds`.** Supplying
only one silently falls back to the built-in anchor for that network rather than
failing — so a partial override looks applied and isn't. `slot-length-ms`
defaults to `1000` and may be omitted.

| Key | Required | Notes |
|---|---|---|
| `id` | yes | Must be a supported network; CIP-34 aliases accepted |
| `required` | no (`true`) | |
| `chain.blockfrost.base-url` | yes | Hosted Blockfrost or a standalone yaci-store's Blockfrost-compatible endpoint (env `BLOCKFROST_BASE_URL`); startup fails without it |
| `chain.blockfrost.project-id` | with hosted Blockfrost | Required for hosted Blockfrost, ignored by yaci-store; keep in secrets, not source (env `BLOCKFROST_PROJECT_ID`) |
| `slot-config.*` | no | Per-network slot↔time anchor; defaults ship in `NetworkClock` |

There's no "at most one" constraint on which target each network points at —
any number of networks may point at hosted Blockfrost, a standalone
yaci-store, or a mix, independently. It's the same client either way,
selected purely by `base-url`.

## Verification

| Key | Default | Notes |
|---|---|---|
| `x402.verification.max-tx-bytes` | `32768` | Bound checked pre-decode (A4) and against protocol `maxTxSize` (C3) |
| `x402.verification.script-datum-policy` | `strict` | `strict` \| `v3-optional` |

`v3-optional` makes the datum optional for `plutusV3` under the `script` method.
`strict` is the safe default: a lock whose validator needs a datum and didn't get
one is unspendable. Only relax it if you know your validator tolerates it.

## Settlement

| Key | Default | Notes |
|---|---|---|
| `x402.settle.confirmation-timeout` | `180s` | How long `/settle` waits |
| `x402.settle.confirmation-depth` | `1` | Blocks before `CONFIRMED` |
| `x402.settle.poll-interval` | `3s` | Inclusion poll cadence |
| `x402.settle.accept-mempool` | `false` | **Keep false** |
| `x402.settle.idempotent-replay` | `false` | Replay a confirmed settlement |
| `x402.settle.stability-window` | `10m` | Rollback watch window |
| `x402.settle.reconcile-horizon` | `24h` | TTL-less expiry fallback |

- **`accept-mempool`** — mempool presence is not payment. **Keep it `false`**
  regardless of chain backend.
- **`confirmation-depth`** — 1 is fine for low-value flows; raise it for
  higher-value ones. It trades latency for rollback resistance.
- **`stability-window`** — how long a `CONFIRMED` row stays under rollback watch.
  Shorter than realistic rollback depth means a rolled-back payment stays
  wrongly confirmed.
- **`idempotent-replay`** — even when on, a replay inside the stability window
  re-checks the chain and demotes rather than returning stale success.

## Duplicate cache / claim TTL

| Key | Default | Notes |
|---|---|---|
| `x402.duplicate-cache.ttl` | `120s` | Also the settlement **claim TTL** |

One key, two jobs: a `CLAIMED` row older than this is considered abandoned by a
dead worker and may be reclaimed. Set it above your realistic submit latency —
too low and a live worker's claim gets stolen mid-flight.

## HTTP

| Key | Default | Notes |
|---|---|---|
| `x402.http.max-request-bytes` | `65536` | Over → `413` |
| `x402.http.cors-allowed-origins` | `[]` | Empty = **no cross-origin access** |

## Masumi

```yaml
x402:
  masumi:
    allowed-script-hashes:
      "cardano:mainnet":
        - "<vested_pay escrow script hash hex>"
```

Per-network escrow script-hash allowlist. **Enforcement is active only when the
list is set and non-empty.** Without it, the `masumi` method only proves the
output went where the *requirements* said — a resource server naming a hostile
address still passes. This is the control that closes that gap; set it before
mainnet.

## Security (opt-in)

Both off by default, so the facilitator stays open unless you opt in.

| Key | Default | Notes |
|---|---|---|
| `x402.security.api-keys` | `[]` | Non-empty ⇒ `X-API-Key` required on `/verify` and `/settle` |
| `x402.security.rate-limit.requests-per-minute` | `0` | `0` = off. Per key, falling back to per IP |

```yaml
x402:
  security:
    api-keys: [ "${FACILITATOR_API_KEY}" ]
    rate-limit:
      requests-per-minute: 120
```

Guards `/verify` and `/settle` only — `/supported`, `/health`, and actuator stay
open for discovery and probes. The rate limiter is a fixed window per
wall-clock minute, in-memory (per instance, not shared across replicas), swept
once per minute so buckets stay bounded under IP rotation.

## Environment variables (Compose)

| Var | Default | Used by |
|---|---|---|
| `DB_PASSWORD`, `DB_PORT` | `facilitator`, `5432` | postgres |
| `BLOCKFROST_BASE_URL` | hosted Blockfrost preprod | facilitator — point at a standalone yaci-store's Blockfrost-compatible endpoint instead to use it |
| `BLOCKFROST_PROJECT_ID` | — | facilitator — required for hosted Blockfrost, ignored by yaci-store |
| `CARDANO_NETWORK` | `preprod` | mithril-sync, node, facilitator |
| `CARDANO_NODE_VERSION` | `10.4.1` | node image tag |
| `YACI_PROTOCOL_MAGIC` | `1` | yaci-store sync (full profile) |
| `MITHRIL_SYNC` | `true` | `false` skips snapshot restore |

See [../deploy/README.md](../deploy/README.md).

## Minimal production example

```yaml
x402:
  networks:
    - id: "cardano:mainnet"
      chain:
        blockfrost:
          base-url: https://cardano-mainnet.blockfrost.io/api/v0
          project-id: ${BLOCKFROST_PROJECT_ID}
  settle:
    confirmation-depth: 3
    accept-mempool: false
  masumi:
    allowed-script-hashes:
      "cardano:mainnet": [ "${MASUMI_SCRIPT_HASH}" ]
  security:
    api-keys: [ "${FACILITATOR_API_KEY}" ]
    rate-limit:
      requests-per-minute: 120
  http:
    cors-allowed-origins: [ "https://your-resource-server.example" ]
```

Work through the [mainnet readiness
checklist](../deploy/README.md#mainnet-readiness-checklist) before going live.
