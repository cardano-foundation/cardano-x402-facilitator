# Configuration Reference

All properties live under the `x402` prefix (`config/X402Properties`), set in
`application.yml` or overridden per environment. Standard Spring relaxed binding
applies, so `x402.settle.confirmation-depth` is
`X402_SETTLE_CONFIRMATIONDEPTH` as an environment variable.

Defaults below are the **code** defaults (the `…OrDefault()` accessors), which
apply when a key is absent entirely. `application.yml` ships explicit values for
most of them; where the shipped value differs from the code default, both are
listed.

## Profiles

| Profile | Effect |
|---|---|
| *(none)* | Blockfrost backend. JPA auto-config excluded. |
| `yaci-store` | Self-hosted indexer. Clears the JPA exclusions, imports the store configs, enables Spring Flyway for the store schema. |

```bash
java -jar facilitator.jar --spring.profiles.active=yaci-store
```

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
schema with its own history table, so the yaci-store schema can be migrated
independently without the two histories colliding.

## Networks

At least one entry is required; startup fails otherwise.

```yaml
x402:
  networks:
    - id: "cardano:preprod"       # cardano:mainnet | cardano:preprod | cardano:preview
      required: true              # default true
      chain:
        mode: blockfrost          # blockfrost | yaci-store
        blockfrost:
          base-url: https://cardano-preprod.blockfrost.io/api/v0
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
| `chain.mode` | yes | `blockfrost` or `yaci-store` |
| `chain.blockfrost.base-url` | with `blockfrost` | Startup fails without it |
| `chain.blockfrost.project-id` | with `blockfrost` | Keep in secrets, not source |
| `slot-config.*` | no | Per-network slot↔time anchor; defaults ship in `NetworkClock` |

**At most one network may use `yaci-store`** — the indexer follows a single
chain. Mixing one Blockfrost network with one yaci-store network is fine; two
yaci-store networks fails at startup.

## Verification

| Key | Default | Notes |
|---|---|---|
| `x402.verification.max-tx-bytes` | `32768` | Bound checked pre-decode (A4) and against protocol `maxTxSize` (C3) |
| `x402.verification.script-datum-policy` | `strict` | `strict` \| `v3-optional` |

`v3-optional` makes the datum optional for `plutusV3` under the `script` method.
`strict` is the safe default: a lock whose validator needs a datum and didn't get
one is unspendable. Only relax it if you know your validator tolerates it.

## Settlement

| Key | Code default | Shipped | Notes |
|---|---|---|---|
| `x402.settle.confirmation-timeout` | `180s` | `180s` | How long `/settle` waits |
| `x402.settle.confirmation-depth` | `1` | `1` | Blocks before `CONFIRMED` |
| `x402.settle.poll-interval` | `3s` | `3s` | Inclusion poll cadence |
| `x402.settle.accept-mempool` | `false` | `false` | **Keep false** |
| `x402.settle.idempotent-replay` | `false` | `false` | Replay a confirmed settlement |
| `x402.settle.stability-window` | `10m` | `10m` | Rollback watch window |
| `x402.settle.reconcile-horizon` | `24h` | `24h` | TTL-less expiry fallback |
| `x402.settle.max-concurrent` | `32` | `32` | |

- **`accept-mempool`** — mempool presence is not payment. Startup *rejects*
  `true` alongside a yaci-store light network, where N2N submission carries no
  node verdict at all.
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

## Chain (yaci-store tuning)

| Key | Default | Notes |
|---|---|---|
| `x402.chain.tip-freshness-slots` | `600` | Within N slots of the tip ⇒ caught up |
| `x402.chain.utxo-unknown-policy` | `fail` | `fail` \| `assume-spent` |

> `x402.chain.era-override` exists in `X402Properties.Chain` but is **not
> implemented** — nothing reads it, so setting it does nothing. The tx body era
> is detected from the indexer (`EraStorage.findCurrentEra()`), falling back to
> Conway. Treat the property as dead until it's wired up or removed.

`utxo-unknown-policy` decides an absent output while the indexer is behind the
tip. `fail` (fail-closed) degrades it to a retryable `chain_lookup_failed`;
`assume-spent` calls it spent, which will reject honest payments near the
horizon. Prefer `fail`.

If the network tip has never been observed, freshness fails open regardless of
this setting — an unknown tip can't prove the indexer is caught up.

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
address still passes. This is the audit-C1 control; set it before mainnet.

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

## yaci-store profile keys

Set in `application-yaci-store.yml`; these are yaci-store's own properties.

| Key | Env | Default | Notes |
|---|---|---|---|
| `store.cardano.host` | `YACI_N2N_HOST` | `localhost` | N2N relay to sync from |
| `store.cardano.port` | `YACI_N2N_PORT` | `3001` | |
| `store.cardano.protocol-magic` | `YACI_PROTOCOL_MAGIC` | `1` | `1` preprod, `2` preview, `764824073` mainnet |
| `store.cardano.n2c-node-socket-path` | `CARDANO_NODE_SOCKET` | `/ipc/node.socket` | Needed for submission |
| `store.utxo.pruning-enabled` | — | `false` | **Leave false** |

**`pruning-enabled: false` is load-bearing.** Spent rows must stay: tri-state
UTxO resolution reads `findById` *plus* a `tx_input` existence check. Pruning
them makes "spent" indistinguishable from "never existed".

Without a `LocalTxSubmissionClient` bean bound to the socket, submission returns
`not_submitted` while verification and confirmation still work off the indexed
stores.

## Environment variables (Compose)

| Var | Default | Used by |
|---|---|---|
| `DB_PASSWORD`, `DB_PORT` | `facilitator`, `5432` | postgres |
| `BLOCKFROST_PROJECT_ID` | — | facilitator (blockfrost) |
| `CARDANO_NETWORK` | `preprod` | mithril-sync, node, facilitator |
| `CARDANO_NODE_VERSION` | `10.4.1` | node image tag |
| `YACI_PROTOCOL_MAGIC` | `1` | yaci-store sync |
| `MITHRIL_SYNC` | `true` | `false` skips snapshot restore |

See [../deploy/README.md](../deploy/README.md).

## Minimal production example

```yaml
x402:
  networks:
    - id: "cardano:mainnet"
      chain:
        mode: blockfrost
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
