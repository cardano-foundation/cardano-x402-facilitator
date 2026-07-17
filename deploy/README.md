# Deploying the Cardano x402 Facilitator

The facilitator exposes the x402 v2 endpoints (`POST /verify`, `POST /settle`,
`GET /supported`) plus `GET /health` and Prometheus metrics at
`/actuator/prometheus`. It always persists settlement state to PostgreSQL and
talks to the chain through one of two interchangeable backends.

## Chain backends

| | **blockfrost** (default) | **yaci-store** |
|---|---|---|
| Indexing | Blockfrost API | self-hosted, embedded indexer |
| Infra | just a Blockfrost project id | a cardano-node (+ Postgres schema) |
| Submission | Blockfrost `tx/submit` | node N2C local socket (era-correct) |
| Profile | none (default) | `--spring.profiles.active=yaci-store` |

Pick one per network entry via `x402.networks[].chain.mode`. `StartupValidation`
allows at most one `yaci-store` network.

## Compose profiles

`deploy/docker-compose.yml` defines two profiles:

- **light** — `postgres` + `facilitator` (blockfrost by default):
  ```bash
  BLOCKFROST_PROJECT_ID=preprod... docker compose --profile light up -d
  ```
- **full** — `postgres` + `mithril-sync` → `cardano-node` → `facilitator-node`
  (yaci-store). Mithril restores a signed node-DB snapshot so the node starts
  near tip instead of syncing from genesis:
  ```bash
  CARDANO_NETWORK=preprod docker compose --profile full up -d
  ```

Key environment variables (all have defaults):

| Var | Default | Used by |
|---|---|---|
| `DB_PASSWORD`, `DB_PORT` | `facilitator`, `5432` | postgres |
| `BLOCKFROST_PROJECT_ID` | — | facilitator (blockfrost) |
| `CARDANO_NETWORK` | `preprod` | mithril-sync, cardano-node, facilitator-node |
| `CARDANO_NODE_VERSION` | `10.4.1` | cardano-node image tag |
| `YACI_PROTOCOL_MAGIC` | `1` (preprod) | yaci-store sync (`764824073` mainnet, `2` preview) |
| `MITHRIL_SYNC` | `true` | mithril-sync (set `false` to skip snapshot restore) |

The app image (`deploy/Dockerfile`) is a glibc Temurin JRE — required because the
`script` assetTransferMethod loads a native aiken UPLC library for parameter
application.

## Verification coverage

All x402 Cardano verifications are implemented and unit-tested against the
TypeScript reference:

- **default** (address-to-address) — asset/amount/recipient/min-UTxO, tri-state
  UTxO replay protection, payer-witness authorization.
- **masumi** (`vested_pay` escrow lock) — the full M1–M9 rule set: contract
  address (+ optional per-network script-hash allowlist), inline datum present /
  no reference script, `FundsLocked` structural invariants, deadline, collateral
  bounds, exact asset set, post-result min-UTxO, and datum field matching.
- **script** — S1 address reconstruction from `scriptHash` or `script`+parameters
  (aiken UPLC apply-params, byte-for-byte conformance-pinned to the reference for
  both empty-params and parametrized vectors), and an S3 Plutus-version-aware
  datum policy (V1 → datum hash; V2 → inline or hash; V3 → configurable; unknown
  → some datum).

## Hardening (spec §13)

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

## yaci-store profile — runtime prerequisites

The yaci-store backend is compile-verified and its logic is unit-tested, but the
**live sync path requires a synced cardano-node and Postgres** and is not
exercised in CI (no node). To run it:

1. Activate the profile: `--spring.profiles.active=yaci-store` (the compose full
   profile sets `SPRING_PROFILES_ACTIVE`).
2. Ensure the yaci-store schema is migrated (`spring.flyway.locations` includes
   `classpath:db/store/postgresql`, set in `application-yaci-store.yml`).
3. Point the indexer at a node: `YACI_N2N_HOST`/`YACI_N2N_PORT` (sync) and
   `CARDANO_NODE_SOCKET` (N2C submission).
4. N2C submission needs a `LocalTxSubmissionClient` bean bound to the node
   socket; until one is provided, submission returns `not_submitted` while
   verification/confirmation still work off the indexed stores. Submission is
   always wired with the **era-correct** `TxBodyType` (never the Babbage-hardcoded
   yaci convenience overload).
5. Pin `yaci-store-events` to `2.0.2` if your resolved graph pulls an older
   transitive version.

## Mainnet readiness checklist

- [ ] Set `x402.networks[].id: cardano:mainnet` and `YACI_PROTOCOL_MAGIC=764824073`.
- [ ] Configure `x402.masumi.allowed-script-hashes.cardano:mainnet` with the
      deployment's genuine `vested_pay` escrow script hash (closes audit C1 —
      enforcement is active only when the allowlist is set).
- [ ] Enable API keys and a rate limit; put the facilitator behind TLS.
- [ ] Confirm `x402.settle.accept-mempool=false` (never grant on mempool).
- [ ] Review `x402.settle.confirmation-depth` (raise above 1 for higher-value flows).
- [ ] Provision the node with adequate resources; verify Mithril restore
      completes and the node reaches tip before serving traffic.
- [ ] For yaci-store: confirm the indexer is caught up (`/health`) and
      `x402.chain.utxo-unknown-policy=fail` (fail-closed on the sync horizon).
- [ ] Rotate the Blockfrost project id / any credentials out of source and into
      secrets management.
