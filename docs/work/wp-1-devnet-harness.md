# WP-1: The devnet + facilitator stack comes up green with one command, on macOS and Linux
Tier: R1
Base: 97add9f
Check: `e2e/run.sh --smoke` exits 0 locally AND the smoke `e2e.yml` (workflow_dispatch) is green on ubuntu-latest; the user pushes the branch

## Allowed files
- e2e/run.sh
- e2e/devnet/**
- e2e/shim/**
- e2e/compose.e2e.yml
- .github/workflows/e2e.yml   (smoke version; WP-8 completes it)
- e2e/postgres/**             (second-database init script; editorial addition)
- .gitignore                  (e2e/.out/, e2e/ts/node_modules/; editorial addition)

## Rules
- DevKit runs as a **compose service** (`devkit`), not on the host. The Linux spike proved this topology; every service reaches the others by name on macOS and Linux alike, with no `host-gateway`.
  - Base image `node:22-bookworm`, `platform: linux/amd64`. Install with `npm install -g @bloxbean/yaci-devkit@0.12.0` at image build time (a small `e2e/devnet/Dockerfile`), so CI caches it.
  - **`unset NODE_VERSION` before `yaci-devkit up`.** The node image sets it (e.g. 22.23.4), and DevKit's Spring relaxed binding reads it as `node.version`, so it tries to download a nonexistent cardano-node and fails. Scrub other collision-prone env too, and add a comment.
  - Start it with `PROTOCOLMAGIC=2 NODEMODE=yano-only` and no `--enable-yaci-store` (the spike ran without it). Companion mode dies at magic 2.
  - Healthcheck: `GET :6060/api/v1/genesis` contains `network_magic`, AND `GET /api/v1/blocks/latest` has a `time` within the last 60 s, so blocks are actually being produced. Expect ~100 s under emulation, less on native x64.
  - DevKit downloads cardano-node, Yano and other components at `up`, not only at install. Pre-fetch them in `e2e/devnet/Dockerfile` (e.g. `yaci-devkit download` or a warm-up `up`/`down` at build time), so the cached image layer holds them and CI doesn't depend on GitHub release downloads on every run. If that isn't possible, record the dependency under Assumed.
  - Publish 6060 (Yano) and 10000 (admin) to the host so tests can fund wallets and check the chain directly.
- Ports are configurable via env: `E2E_YANO_HTTP_PORT` 6060, `E2E_SHIM_PORT` 16061, `E2E_FACILITATOR_PORT` 4022, `E2E_FACILITATOR_MEMPOOL_PORT` 4023, admin 10000. Never kill a foreign process holding a port; fail with a clear message.
- Shim (`python:3.12-alpine` compose service, so it can be paused):
  - Base it on ThreadingHTTPServer.
  - Rewrite ONLY the `system_start` field of `GET */genesis` (ISO string → epoch seconds). Decode gzip if the upstream sends it, and recompute Content-Length.
  - Pass everything else through unchanged, with status codes and bodies preserved.
  - Count every `POST */tx/submit` by sha256 of the raw body and expose `GET /__shim/submits?sha256=<hex>` → `{"count":n}`. This path is namespaced and never forwarded.
  - A header comment explains why the shim exists and leaves a placeholder for the upstream issue link.
- Compose services (one network; the shim's upstream is `http://devkit:6060`, and facilitator base-url is `http://shim:16061/api/v1/`):
  - `devkit` (above)
  - `postgres`, with an init script that creates a second database `facilitator_mempool`
  - `shim`
  - `facilitator` (defaults, `DB_URL=…/postgres`)
  - `facilitator-mempool` (`X402_SETTLE_ACCEPTMEMPOOL=true`, `DB_URL=…/facilitator_mempool`, same NETWORKS_FILE)
  - Both facilitators come from `E2E_IMAGE` (built from the repo Dockerfile with `--platform linux/amd64` if absent).
- The two journals must be isolated by **database**, not schema. `FlywayConfig.java:22-28` hardcodes the `facilitator` schema and `SettlementRepository` hardcodes `facilitator.settlement`, so `DB_SCHEMA` has no effect.
- Masumi constants: `e2e/devnet/masumi-deployment.json` holds fixed devnet params (e.g. requiredAdmins 1, one fixed admin vkey, cooldownPeriod 0) plus the precomputed script hash. Compute the hash once with main's MasumiBlueprint via a throwaway jshell/JavaExec and commit the value. WP-5 adds a consistency test.
- Startup order: `devkit` healthy → render NETWORKS_FILE → start `shim`, `postgres` and both facilitators. The slot-config depends on the devnet's `startTime`, so rendering must come after devkit is up and before the facilitators start.
- `render-networks.py`:
  - Reads `GET :10000/local-cluster/api/admin/devnet` (`startTime`, `slotLength`).
  - Writes NETWORKS_FILE with `cardano:preview`, base-url = shim, and `slot-config`.
  - Sets `x402.masumi.allowed-script-hashes.cardano:preview` from masumi-deployment.json.
- `run.sh`:
  - Modes: `--smoke`, `--java [gradle args]`, `--ts`, and all by default.
  - The Java pass runs `e2eTest` excluding `@Tag("outage")`, then a second invocation with only that tag, so the outage tests run last.
  - Exports `FACILITATOR_URL`, `FACILITATOR_MEMPOOL_URL`, `E2E_CHAIN_URL` (Yano direct), `E2E_SHIM_URL`, `E2E_ADMIN_URL` and `E2E_COMPOSE_PROJECT`.
  - Always tears down unless `--keep`; on failure prints the facilitator, devnet and shim log tails.
- Smoke `e2e.yml`: triggered by workflow_dispatch only. Builds the image, runs `e2e/run.sh --smoke`, and uploads logs.
- Out of scope: tests.

## Proof commands
- red-first: n/a (harness); record `no new tests`
- green: e2e/run.sh --smoke ; ubuntu smoke workflow run URL recorded in Log
- shim: curl genesis via shim shows an integer system_start; a tx submitted through the shim makes the counter read 1
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-1-devnet-harness.md

## Log
- 2026-10-26 red-first: no new tests (harness).
- 2026-10-26 Found: `yaci-devkit download` (0.12.0) prints `true` when done but never exits, which hung the image build for hours. Added `e2e/devnet/prefetch.sh`: it backgrounds the download, waits for the result line (600 s cap), then kills the CLI. The devkit image now builds in 91 s.
- 2026-10-26 green (local, macOS/arm64, amd64 emulated): `e2e/run.sh --smoke` exit 0 in 70 s (default ports were held by another session's DevKit; reran with E2E_*_PORT overrides; the preflight caught it as designed).
- 2026-10-26 green (CI): the user reports the smoke workflow passing on ubuntu-latest (run URL not recorded).
- 2026-10-26 Deviation: the smoke workflow triggers on pull_request, push to main and workflow_dispatch (not dispatch-only), so the user could see it run on the PR.
- 2026-10-26 Not yet proven: the shim submit counter (a tx submitted via the shim reads count 1). First exercised by WP-2/3 settle tests.
- 2026-10-26 scope: the only out-of-scope paths are the plan/work docs (orchestrator-owned).

- 2026-10-27 Later edits to WP-1 files by the orchestrator (deviations, all re-verified by full cold runs):
  - e2e/devnet/masumi-deployment.json: admin vkeys were 29 bytes; now 28, hashes recomputed (TS SDK and Java MasumiBlueprint agree). Found by the WP-7 spike.
  - e2e/shim/yano_genesis_shim.py: `/sdk/api/v1` view for the TS SDK (adds utxo `tx_index`, stringifies drep/gov deposits); user-approved. The facilitator's `/api/v1` view is unchanged.
  - e2e/run.sh: always dump logs; per-pass JUnit dirs; E2E_SDK_CHAIN_URL; explicit-args branch keeps the outage exclusion; full mode runs both suites and fails at the end; each step returns its own failure.
- 2026-10-27 Scope: check-scope.sh diffs the whole branch, so per package it lists every other package's files. Union check (scratchpad union_scope.py): all 56 changed files are owned by some package's Allowed files, except the 10 plan/work docs (orchestrator-owned).
