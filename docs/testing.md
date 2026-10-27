# Testing

Five layers, in increasing order of what they prove:

| Layer | Command | Needs | Proves |
|---|---|---|---|
| Unit + slice | `./gradlew test` | nothing | Rules, wire shapes, state machine |
| Postgres IT | `./gradlew test` | Docker | Real CAS/claim semantics |
| TypeScript HTTP interop | `./gradlew interop` | Node + `npm ci --prefix interop` | Published SDKs accept Java HTTP responses and signed payments |
| **Devnet E2E** (CI, every PR) | `e2e/run.sh` | Docker, Java 21, Node 22, python3, curl | The shipped container, on a real ledger: every route, every transfer method, every reachable error code |
| **On-chain E2E** | `./gradlew e2e` | funded wallet + running facilitator | It actually works on preprod |

Java 21 is required:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew test
```

## Unit and slice tests

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test
```

No live-chain access or credentials are needed for the automated suite. Tests
cover ordinary verification, strict Masumi schema/signatures, script descriptors,
provider evidence, durable settlement, and V1 upgrade behavior. PostgreSQL tests
require Docker.

`FakeChainService` (`testutil/`) drives every `UtxoState` / `InclusionResult` /
`SubmissionResult` branch — including the `Unknown` paths, which are the ones you
cannot reproduce against a real backend on demand and are exactly where the
dangerous bugs live. `TestTx` builds transaction fixtures, and `MasumiTestSeller`
produces genuine CIP-8 authorizations so a Masumi fixture only passes if the
terms it asserts are terms that seller actually signed.

`FakeChainService.includedDepth` uses the spec's evidence ladder rather than a
truthiness sentinel: `NOT_SEEN` (-2), `MEMPOOL` (-1), and `>= 0` for canonical
inclusion with that many newer blocks. Zero means **included**, not absent.

`ScriptAddressConformanceTest` pins aiken UPLC apply-params output byte-for-byte
against known-good vectors, for both empty and parametrized cases. If it fails
after a dependency bump, the script method's address reconstruction has
silently changed — do not "fix" the vectors.

`SettlementPostgresIT` uses Testcontainers, so **Docker must be running**. It
exercises the CAS transitions against real Postgres; H2 won't catch the
concurrency semantics the reconciler depends on.

## TypeScript interoperability

```bash
npm ci --prefix interop --ignore-scripts
./gradlew interop
```

This starts a loopback-only Java server with the real controller, verifier,
settlement service and migrated H2 journal. Chain input quantities, submission
outcomes and inclusion evidence are controlled by test-only routes. The
published `HTTPFacilitatorClient` and `x402ResourceServer` from `@x402/core` 2.26.0
call its actual HTTP endpoints. Assertions cover capabilities, all three transfer
methods, ADA/tokens, network aliases, the automatic pending retry, provider
outages, rollback, rejection and exactly one broadcast.

`interop/fixtures.mjs` independently creates eight signed payment vectors using
`@x402/cardano` 2.26.0 and Evolution, verifies them against the TypeScript
facilitator, and checks ten mutated rejection cases before writing
`src/test/resources/upstream/payments.json`. Regenerate
with `npm run fixtures --prefix interop`. The seed is a public BIP-39 test vector;
no live provider or funded wallet is used. Java also checks the resulting Masumi
commitments and JCS digests. See [compatibility notes](upstream-compatibility.md)
for the precise upstream commit and behavioral boundaries.

## Devnet E2E

A black-box suite against the **container image** this repo ships. `e2e/run.sh`
starts a [Yaci DevKit](https://github.com/bloxbean/yaci-devkit) devnet that poses
as `cardano:preview` (protocol magic 2, 1 s slots), points two facilitator
containers at it, and drives them over HTTP with real signed transactions. The
`E2E (devnet)` workflow runs it on every PR and every push to `main`.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home  # Gradle 8.14 needs ≤ 21
e2e/run.sh                     # stack + TS SDK suite + Java suite (+ outage pass, last)
e2e/run.sh --java --tests '*RejectionMatrixE2ETest'   # one class
e2e/run.sh --ts                # just the TypeScript SDK suite
e2e/run.sh --smoke --keep      # just the stack, left running for manual pokes
```

The facilitator image is built from `./Dockerfile` if `x402-facilitator:e2e`
(or `$E2E_IMAGE`) is absent. Host ports default to 6060/10000/16061/4022/4023;
if one is taken the preflight stops with a message — override with
`E2E_YANO_HTTP_PORT`, `E2E_ADMIN_PORT`, `E2E_SHIM_PORT`, `E2E_FACILITATOR_PORT`,
`E2E_FACILITATOR_MEMPOOL_PORT`. Container logs land in `e2e/.out/logs/`; JUnit XML in `e2e/.out/junit/` for full runs (with explicit `--java` arguments, Gradle's usual `build/test-results/e2eTest/`).

### The stack

| Service | What | Why |
|---|---|---|
| `devkit` | npm `@bloxbean/yaci-devkit` 0.12.0, `yano-only`, magic 2 | The ledger. Components are baked into the image (`e2e/devnet/prefetch.sh`). |
| `shim` | `e2e/shim/yano_genesis_shim.py` in front of Yano | Blockfrost compatibility + a submit counter (below) |
| `postgres` | two databases | One journal per facilitator instance |
| `facilitator` | the image, defaults | `accept-mempool=false` |
| `facilitator-mempool` | the image, `X402_SETTLE_ACCEPTMEMPOOL=true` | The `l1Confirmations: -1` → `mempool` path |

`e2e/devnet/render-networks.py` writes the facilitators' `NETWORKS_FILE` from the
live devnet: the genesis start time as `slot-config` (it changes every run), the
shim as the Blockfrost base URL, and the fixed Masumi deployment in
`e2e/devnet/masumi-deployment.json` as the `cardano:preview` allowlist.

**Why the shim exists.** Yano's Blockfrost-compatible API differs from Blockfrost
in three places this suite hits:

- `GET /genesis` returns `system_start` as an ISO string; cardano-client-lib
  expects integer seconds, so the facilitator's readiness would stay DOWN. The
  shim rewrites that one field for everyone.
- `GET /addresses/{a}/utxos` omits `tx_index`, and `GET /epochs/latest/parameters`
  returns `drep_deposit`/`gov_action_deposit` as numbers. The Evolution SDK's
  provider rejects both. The shim fixes them only under `/sdk/api/v1/…`, the
  TypeScript SDK's endpoint; the facilitator's view stays a plain pass-through.

The shim also counts `POST */tx/submit` per body hash
(`GET /__shim/submits?sha256=…`), which is how the suite proves "broadcast exactly
once" and "never broadcast". On-chain proof always reads Yano **directly**, never
through the shim or the facilitator.

### What it covers

| Class | Covers |
|---|---|
| `RoutesE2ETest` | Every route's contract: one real verify+settle, exact `/supported` (both instances), `/health`, actuator probes, Prometheus, correlation ids, 400/413/500 bodies |
| `DefaultTransferE2ETest` | ADA, native token, aux metadata, overpay — verify, settle, Yano inclusion, one broadcast |
| `SettlementSemanticsE2ETest` | `l1Confirmations` 0/1/3/10/-1 per instance, idempotent re-settle, `duplicate_settlement`, spent nonce, a real concurrent-settle race |
| `RejectionMatrixE2ETest` | 39 single-defect cases: a valid control, then exactly one code on `/verify` **and** `/settle`, nothing broadcast |
| `ScriptTransferE2ETest`, `MasumiTransferE2ETest` | Script (V2, V3, parametrized, hash-only) and Masumi (ADA, token) locks on-chain, plus every method-specific rejection |
| `ChainOutageE2ETest` (`@Tag("outage")`, runs last) | Shim paused: 503 health, readiness DOWN, `chain_lookup_failed`, 503 fresh settle, journaled retry → `settlement_pending`; then recovery |
| `e2e/ts` (node:test) | The **published** `@x402/cardano`/`@x402/core` 2.26.0: resource server issues, reference signer pays, `HTTPFacilitatorClient` settles — default ADA/token, script, Masumi ADA/token |
| `ErrorCodeCoverageTest` (stackless) | Every `ErrorCodes` constant is asserted by a named test or excluded with a reason |

The coverage gate reads `ErrorCodes.java` as source and merges
`e2e/coverage/wp-*.yml`. A **new error code fails CI** until someone maps it. The
current exclusions, with reasons in `e2e/coverage/wp-4.yml`:

- unreachable over HTTP: `UNSUPPORTED_VERSION` (the registry answers 500 first)
- provider faults a healthy devnet can't produce: `INPUT_VALUE_UNAVAILABLE`, `SETTLEMENT_FAILED`
- race-only: `SETTLEMENT_DEFINITIVELY_REJECTED`
- dead (never returned by main): `SETTLEMENT_NOT_CONFIRMED`, `SUBMISSION_MODE_MISMATCH`, `SUBMISSION_MODE_UNSUPPORTED`, `EVIDENCE_MISSING`, `CLIENT_SCRIPT_EXECUTION`

### Known gaps

- **Yano is not cardano-node.** "Accepted by Yano" is weaker evidence than the
  real ledger. The provider's `Rejected` classification (`BlockfrostChainService`)
  probably never matches Yano's error text, so ledger rejection is untested here.
- **Yano never reports a pending tx on `/mempool/{hash}`**, so the facilitator's
  mempool-evidence branches only see `NotSeen` → `Included` on the devnet.
- **Pinned as-is, not designed:** wrong method / content type / path return
  `500 internal_error` (the `ApiErrorAdvice` catch-all swallows Spring's
  405/415/404), and a 500 body's `correlationId` differs from the
  `X-Correlation-Id` header. `RoutesE2ETest` pins the first so a change is
  deliberate.
- `docs/api.md` describes `401`/`429` filters; none exist in the code, so
  nothing tests them.

## The on-chain E2E proof

`X402PreprodE2E` is the acceptance test: everything above can pass while the
service still fails against a real chain. It builds and signs a real transaction
with cardano-client-lib, runs it through `/verify` and `/settle`, and then
**independently confirms the tx on-chain** — looking up the *locally computed*
hash via the provider directly, never taking the facilitator's word for it.

```
1. build + sign with cardano-client-lib   (the facilitator never signs)
2. POST /verify   → must return isValid: true, payer == our address
3. POST /settle   → facilitator submits and confirms
4. poll the chain for the LOCAL hash → prove inclusion in a block
```

### Running it

The facilitator must already be running and reachable:

```bash
# terminal 1
BLOCKFROST_PROJECT_ID=preprod... ./gradlew bootRun

# terminal 2
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
BLOCKFROST_PROJECT_ID=preprod... E2E_MNEMONIC="..." ./gradlew e2e
```

Configuration:

| Var | Default | Notes |
|---|---|---|
| `BLOCKFROST_PROJECT_ID` | required | Provider credential |
| `E2E_MNEMONIC` | required | Dedicated testnet payer; needs funds |
| `E2E_BACKEND_URL` | preprod Blockfrost | Repoint for devnet |
| `FACILITATOR_URL` | `http://localhost:4022` | |
| `E2E_NETWORK` | `cardano:preprod` | |
| `E2E_AMOUNT_LOVELACE` | `1500000` | |

Payer is the mnemonic's address index 0, receiver is index 1, so the wallet pays
itself and only fees are consumed. Fund index 0 from the [preprod
faucet](https://docs.cardano.org/cardano-testnets/tools/faucet).

No credentials are embedded in the harness. Rotate the previously committed
preprod project ID and mnemonic at their provider/wallet; removing them from
source does not revoke their historical exposure. Pass both required variables
through a local secret store, and use a dedicated testnet wallet.

### Reading the result

Success ends with:

```
ON-CHAIN CONFIRMED: tx <hash> in block <block>
  https://preprod.cardanoscan.io/transaction/<hash>
```

Two outcomes are **not** failures:

- `success: false` with `errorReason: settlement_pending` —
  the tx was broadcast and confirmation timed out. The harness keeps polling the
  chain for up to 5 minutes, because a timeout is not a rejection.
- Any other `/settle` failure **is** a hard failure and throws.

The harness also asserts the facilitator returns *the same* tx hash it computed
locally. A different hash means the facilitator submitted something other than
what was signed — which would be a serious bug, so it's checked rather than
assumed.

For devnet runs, use the [Devnet E2E](#devnet-e2e) suite instead: it covers this
flow and much more, on every PR.

## What isn't covered

- **The full self-hosted stack.** The `full` Compose profile (Mithril +
  cardano-node + yaci-store) needs a live, synced node, which CI doesn't have,
  so it isn't exercised end-to-end. The Devnet E2E suite runs the same
  `BlockfrostChainService` code path against Yano behind a compatibility shim —
  only the full stack's integration (and a real cardano-node ledger) is unproven.
- **Mainnet and preprod in CI.** CI runs against the devnet only; the preprod
  proof (`./gradlew e2e`) is manual. Nothing here has run against mainnet.

## Before committing

Per the repo workflow: run the real suite and read the output — a green claim
without it is worthless.

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test
```

Expect `BUILD SUCCESSFUL` with no test failures.

For the complete upgrade gate, run `./gradlew clean test bootJar interop` after
installing the pinned interop dependencies.
