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

`deploy/docker-compose.yml` defines three profiles:

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

Key environment variables:

| Var | Default | Used by |
|---|---|---|
| `POSTGRES_ADMIN_PASSWORD`, `FACILITATOR_DB_PASSWORD`, `YACI_DB_PASSWORD` | required, distinct | database administrator and separate application roles |
| Facilitator API host port | `127.0.0.1:4022` | local published listener in every Compose profile |
| `BLOCKFROST_BASE_URL` | hosted Blockfrost for `CARDANO_NETWORK` (light) / yaci-store URL (full, hardcoded) | facilitator |
| `BLOCKFROST_PROJECT_ID` | — | facilitator — required for hosted Blockfrost, ignored by yaci-store |
| `CARDANO_NETWORK` | `preprod` | all profiles: canonical facilitator ID, hosted URL, node, indexer magic and Yano |
| `CARDANO_NODE_VERSION` | `10.4.1` | cardano-node image tag |
| `SYNC_START_SLOT`, `SYNC_START_BLOCKHASH` | preprod checkpoint; origin on mainnet/preview | Optional matching checkpoint for yaci-store |
| `MITHRIL_SYNC` | `true` | mithril-sync (set `false` to skip snapshot restore) |

The app image ([Dockerfile](../Dockerfile)) uses a glibc Temurin JRE and
`linux/amd64`. The pinned `aiken-java-binding:0.1.0` includes a Linux x86-64
native library for Masumi and script parameter application, but no Linux ARM64
library. All facilitator Compose services select `linux/amd64`, including on
Apple Silicon, where Docker Desktop runs them under emulation. Direct image
builds must likewise use `docker build --platform linux/amd64 ...`.

The Docker build runs the offline Masumi and script derivation tests on the
target platform. Missing or incompatible native libraries fail the build rather
than first appearing as a payment-time HTTP 500.

### PostgreSQL roles and existing volumes

On a fresh volume, `deploy/postgres/10-roles.sh` creates two non-superuser
login roles. `facilitator` owns its settlement schema and Flyway history;
`yaci_store` owns its own schema. Neither can access the other's private
schema. Each has `CONNECT, CREATE` on the shared `postgres` database because
the immutable V1 migration executes `CREATE SCHEMA IF NOT EXISTS`, which
requires database CREATE even if the schema already exists. Thus these roles
can create another schema, but cannot create databases or roles or become
administrators. PostgreSQL has no published host port.

For an **existing** `pgdata` volume, changing environment passwords alone
does not update PostgreSQL roles and init scripts do not rerun. Before starting
the new facilitator/Yaci images:

1. Stop all facilitator and Yaci writers; make and verify a database backup.
2. Set the three required password variables in the deployment environment.
3. Inspect the existing volume's `pg_hba.conf` and replace any TCP `trust`
   authentication rules with `scram-sha-256`, preserving intentional
   addresses/TLS/reject rules. Reload PostgreSQL and verify a wrong password
   is rejected over the container network. The upgrade script fails closed
   while a TCP trust rule remains. The Compose `POSTGRES_INITDB_ARGS` setting
   hardens fresh volumes only; it does not rewrite an existing `pg_hba.conf`.
4. Start only PostgreSQL with the existing volume, then run
   `docker compose -f deploy/docker-compose.yml --profile light exec -u postgres postgres sh /opt/x402/upgrade-existing.sh`.
5. Confirm the script succeeds, then start the selected profile. The upgrade
   changes only the two application schemas and their contained objects in one
   transaction; it preserves Flyway history and does not reset journal rows.
   The script also rotates the existing administrator password to
   `POSTGRES_ADMIN_PASSWORD`.

Use a separate Compose project for each network. Do not delete the existing
volume to make the new configuration start.

If an older ARM64 image reports `UnsatisfiedLinkError` for
`libaiken_jna_wrapper.so`, rebuild and recreate the facilitator from the project
root, using the same environment settings as the existing deployment:

```bash
docker compose -f deploy/docker-compose.yml --profile light up -d --build --no-deps facilitator
```

For the other profiles, use their corresponding facilitator service. This leaves
the PostgreSQL service and its settlement journal in place.

## Verification coverage

All three transfer methods (`default`, `masumi`, `script`) use facilitator
submission, with durable retry reconciliation and pinned TypeScript HTTP
interoperability tests. The rules, in order and with their error
codes, are in [docs/verification.md](../docs/verification.md) — not repeated here,
because a second copy is a copy that goes stale.

## Upgrading to upstream 2.26.0

Read the [compatibility and migration notes](../docs/upstream-compatibility.md)
before rollout. V2 adds durable confirmation policy, submission provenance and a
unique Masumi terms claim. Historical V1 rows remain observation-only until a
validated retry and independent evidence establish their outcome. Preserve the
journal and upgrade all facilitator writers together; mixed old/new writers do
not enforce the same claims. The live preprod proof has not been rerun for this
upgrade.

## Hardening

Enabled by default:

- **Request size** — `X-*` byte cap (`x402.http.max-request-bytes`, default 64 KiB)
  → 413; Jackson `StreamReadConstraints` bound nesting/string/number length.
- **Correlation id** — every request carries `X-Correlation-Id` (echoed + logged
  under `%X{correlationId}`); error bodies are sanitized.
- **Settlement gate** — fresh `POST /settle` requests return 503 when the chain
  backend is unhealthy. Journaled retries reach reconciliation and can return
  `settlement_pending` without another broadcast.
- **Readiness** — `/health` and Actuator readiness return unavailable for an
  unhealthy required network, including stale or future provider tips.
  Optional network failures are visible without taking the service offline.
- **CORS** — default-deny; opt origins in via `x402.http.cors-allowed-origins`.

The facilitator does not authenticate or rate-limit HTTP callers. Compose
publishes its API only on the host's loopback interface. Other containers on
the Compose network can still reach it, and a direct JAR launch does not
inherit this host binding. The former `x402.security.*`, `FACILITATOR_API_KEY`,
and `FACILITATOR_RATE_LIMIT_RPM` settings no longer protect the API. A remotely
reachable deployment needs operator-managed TLS, authentication, and traffic
limits at ingress. See [API exposure](../docs/configuration.md#api-exposure).

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
- [ ] Configure `x402.masumi.allowed-script-hashes.cardano:mainnet` with your
      deployment's `vested_pay` escrow script hash, to serve only that one. The
      address is derived and checked regardless; the allowlist narrows it.
- [ ] Set distinct database passwords; put the loopback-bound facilitator
      behind an authenticated, traffic-limited TLS ingress before remote use.
- [ ] Confirm `x402.settle.accept-mempool=false` (never grant on mempool).
- [ ] Set `extra.confirmationPolicy.l1Confirmations` in resource-server quotes
      to the depth appropriate for the payment (default 1); the old process
      `confirmation-depth` setting no longer overrides it.
- [ ] Provision the node with adequate resources; verify Mithril restore
      completes and the node reaches tip before serving traffic.
- [ ] For yaci-store: confirm the instance is close to tip before serving
      traffic. Like hosted Blockfrost, it resolves an absent output straight to
      `Spent` — a stale yaci-store can reject honest payments as replays.
- [ ] Rotate the Blockfrost project id / any credentials out of source and into
      secrets management.

## Consistent network selection

Set `CARDANO_NETWORK` to `preprod`, `preview` or `mainnet` for any Compose profile.
Compose derives `X402_NETWORK_ID=cardano:<network>` for each facilitator and selects
the corresponding hosted Blockfrost URL unless `BLOCKFROST_BASE_URL` is overridden.
The full profile loads matching indexer magic from `networks/<network>.env`;
`YACI_PROTOCOL_MAGIC` is no longer an independent selector. Preprod retains its
existing checkpoint; mainnet/preview start from origin unless you provide both
`SYNC_START_SLOT` and `SYNC_START_BLOCKHASH` for that network.
[Yaci Store's start logic](https://github.com/bloxbean/yaci-store/blob/main/components/core/src/main/java/com/bloxbean/cardano/yaci/store/core/service/StartService.java)
uses origin when slot is zero or the checkpoint hash is absent. Origin indexing may
require a full-history node; choose an available matching checkpoint when using a
pruned snapshot. No network synchronization was performed by the regression test.

Yano's network and default profile follow `CARDANO_NETWORK`; `YANO_NETWORK` is no
longer an independent selector. If overriding `YANO_PROFILE` for additional features,
keep its network profile consistent. Custom backend URL overrides must point to the
selected network. Direct application launches still use `X402_NETWORK_ID`.

Verify all three network configurations without starting containers:

```sh
python3 deploy/test-network-config.py
```

Changing a selector does not convert existing node/indexer database volumes to a
different network. Use a separate Compose project/volumes for a different network.

Configured Blockfrost-compatible providers must expose `/genesis` with the matching
network magic (`764824073` mainnet, `1` preprod, `2` preview). Identity is checked
on health and payment chain access; mismatch or unavailable identity fails closed
and no transaction is submitted. Successful identity checks are cached for 30 seconds.
Application construction and wall-clock slot calculation remain offline; a provider
that is still starting makes health/payment checks fail until its identity is available.
The latest block must be within `x402.chain.max-tip-age` (default five minutes)
and no more than 30 seconds ahead of the local clock. The bundled provider
cannot prove that its transaction index has caught up to that tip, so even
transaction and mempool 404 responses leave an unobserved settlement pending
after TTL. The durable claim prevents repeat submission. Investigate these
rows with independent chain evidence; do not delete claims or manually
rebroadcast. An unavailable or unsupported mempool endpoint also leaves the
settlement pending. See [settlement evidence](../docs/configuration.md) for
the custom-provider proof requirement.

## E2E credentials

The optional `./gradlew e2e` proof requires `BLOCKFROST_PROJECT_ID` and
`E2E_MNEMONIC` from the environment. The harness contains no credential
fallbacks. Rotate any previously committed preprod project ID and mnemonic
at their providers/wallet, since removing them from source does not revoke
their historical exposure. Fund and use a dedicated testnet wallet only.
