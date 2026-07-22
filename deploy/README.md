# Deploying the Cardano x402 Facilitator

The facilitator exposes the x402 v2 endpoints (`POST /verify`, `POST /settle`,
`GET /supported`) plus `GET /health` and Prometheus metrics at
`/actuator/prometheus`. It always persists settlement state to PostgreSQL and
talks to the chain through a single Blockfrost-compatible client.

## Chain backend

There is exactly one chain backend: the cardano-client-lib Blockfrost provider.
Point it at hosted Blockfrost or at a standalone yaci-store instance — same
client, same code path, just a different `BLOCKFROST_BASE_URL`:

| | Hosted Blockfrost (default) | Standalone yaci-store |
|---|---|---|
| Infra | just a Blockfrost project id | a `yaci-store` deployment (its own cardano-node + Postgres) |
| Submission | Blockfrost `tx/submit` | yaci-store's `/tx/submit`, forwarded to its node |
| `BLOCKFROST_BASE_URL` | Blockfrost hosted API (default) | e.g. `http://yaci-store:8080/api/v1/blockfrost` |
| `BLOCKFROST_PROJECT_ID` | required | ignored |

Set per network entry via `x402.networks[].chain.blockfrost.base-url`. The
facilitator does not embed an indexer either way.

## Compose profiles

`deploy/docker-compose.yml` defines two profiles:

- **light** — `postgres` + `facilitator` (hosted Blockfrost by default). To
  point it at a standalone yaci-store instead, set `BLOCKFROST_BASE_URL` to its
  Blockfrost-compatible endpoint on the `facilitator` service:
  ```bash
  BLOCKFROST_PROJECT_ID=preprod... docker compose --profile light up -d
  ```
- **full** — `postgres` + `mithril-sync` → `cardano-node` → **`yaci-store`** →
  `facilitator-node`. Mithril restores a signed node-DB snapshot so the node
  starts near tip instead of syncing from genesis; `yaci-store` then syncs from
  the node over N2N and submits over its N2C socket, exposing a
  Blockfrost-compatible API that `facilitator-node` consumes
  (`BLOCKFROST_BASE_URL=http://yaci-store:8080/api/v1/blockfrost`):
  ```bash
  CARDANO_NETWORK=preprod docker compose --profile full up -d
  ```

Key environment variables (all have defaults):

| Var | Default | Used by |
|---|---|---|
| `DB_PASSWORD`, `DB_PORT` | `facilitator`, `5432` | postgres |
| `BLOCKFROST_BASE_URL` | hosted Blockfrost preprod (light) / yaci-store URL (full, hardcoded) | facilitator |
| `BLOCKFROST_PROJECT_ID` | — | facilitator — required for hosted Blockfrost, ignored by yaci-store |
| `CARDANO_NETWORK` | `preprod` | mithril-sync, cardano-node, facilitator-node |
| `CARDANO_NODE_VERSION` | `10.4.1` | cardano-node image tag |
| `YACI_PROTOCOL_MAGIC` | `1` (preprod) | yaci-store sync (`764824073` mainnet, `2` preview) |
| `MITHRIL_SYNC` | `true` | mithril-sync (set `false` to skip snapshot restore) |

The app image (`deploy/Dockerfile`) is a glibc Temurin JRE — required because the
`script` assetTransferMethod loads a native aiken UPLC library for parameter
application.

## Verification coverage

All x402 Cardano verifications are implemented and unit-tested:

- **default** (address-to-address) — asset/amount/recipient/min-UTxO, tri-state
  UTxO replay protection, payer-witness authorization.
- **masumi** (`vested_pay` escrow lock) — the full M1–M9 rule set: contract
  address (+ optional per-network script-hash allowlist), inline datum present /
  no reference script, `FundsLocked` structural invariants, deadline, collateral
  bounds, exact asset set, post-result min-UTxO, and datum field matching.
- **script** — S1 address reconstruction from `scriptHash` or `script`+parameters
  (aiken UPLC apply-params, verified byte-for-byte against known-good vectors
  for both empty-params and parametrized cases), and an S3 Plutus-version-aware
  datum policy (V1 → datum hash; V2 → inline or hash; V3 → configurable; unknown
  → some datum).

## Hardening

Enabled by default:

- **Request size** — `X-*` byte cap (`x402.http.max-request-bytes`, default 64 KiB)
  → 413; Jackson `StreamReadConstraints` bound nesting/string/number length.
- **Correlation id** — every request carries `X-Correlation-Id` (echoed + logged
  under `%X{correlationId}`); error bodies are sanitized.
- **Settlement gate** — `POST /settle` returns 503 when the chain backend is
  unhealthy, rather than accepting a settlement it cannot confirm.
- **CORS** — default-deny; opt origins in via `x402.http.cors-allowed-origins`.

Opt-in (off unless configured):

- **API keys** — `x402.security.api-keys` requires `X-API-Key` on `/verify` and
  `/settle` (401 otherwise).
- **Rate limit** — `x402.security.rate-limit.requests-per-minute` per key/IP
  fixed-window (429 + `Retry-After` otherwise).

## Running against yaci-store

The `full` Compose profile brings up a complete self-hosted stack: Mithril
restores a synced node-DB, `cardano-node` joins the network, `yaci-store` syncs
from it and exposes a Blockfrost-compatible API, and `facilitator-node` is wired
to consume it (`BLOCKFROST_BASE_URL=http://yaci-store:8080/api/v1/blockfrost`).
That stack requires a live network connection and is not exercised in CI:

```bash
CARDANO_NETWORK=preprod docker compose --profile full up -d
```

To point the facilitator at a different, already-running yaci-store instance —
including under the `light` profile — set `BLOCKFROST_BASE_URL` to its
Blockfrost-compatible base URL (e.g.
`http://your-yaci-store-host:8080/api/v1/blockfrost`); no other facilitator-side
configuration is required. yaci-store's own sync configuration (node host/port,
protocol magic, N2C socket, sync-start intersect) is set on the `yaci-store`
service itself — see that service's block in `deploy/docker-compose.yml`.

## Mainnet readiness checklist

- [ ] Set `x402.networks[].id: cardano:mainnet` and `YACI_PROTOCOL_MAGIC=764824073`.
- [ ] Configure `x402.masumi.allowed-script-hashes.cardano:mainnet` with the
      deployment's genuine `vested_pay` escrow script hash (enforcement is
      active only when the allowlist is set).
- [ ] Enable API keys and a rate limit; put the facilitator behind TLS.
- [ ] Confirm `x402.settle.accept-mempool=false` (never grant on mempool).
- [ ] Review `x402.settle.confirmation-depth` (raise above 1 for higher-value flows).
- [ ] Provision the node with adequate resources; verify Mithril restore
      completes and the node reaches tip before serving traffic.
- [ ] For yaci-store: confirm the instance is close to tip before serving
      traffic. Like hosted Blockfrost, it resolves an absent output straight to
      `Spent` — a stale yaci-store can reject honest payments as replays.
- [ ] Rotate the Blockfrost project id / any credentials out of source and into
      secrets management.
