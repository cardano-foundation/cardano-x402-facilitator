# Testing

Six layers, in increasing order of what they prove:

| Layer | Command | Needs | Proves |
|---|---|---|---|
| Unit + slice | `./gradlew test` | nothing | Rules, wire shapes, state machine |
| Coverage gates | `./gradlew test` | nothing | Every error code and transfer method is mapped to an e2e test or excluded with a reason |
| Postgres IT | `./gradlew test` | Docker | Real CAS/claim semantics |
| TypeScript HTTP interop | `./gradlew interop` | Node + `npm ci --prefix interop` | Published SDKs accept Java HTTP responses and signed payments |
| **Devnet E2E** (CI, every PR) | `e2e/run.sh` | Docker, Java 21, Node 22, python3, curl | The shipped container, on a real ledger: every route, every transfer method, every reachable error code, provider faults, restarts and load |
| **On-chain E2E** | `./gradlew preprodE2e` | funded wallet + running facilitator | It actually works on preprod |

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
`Build` workflow runs it on pull requests, on pushes to `main`, `develop`, `release/*` and version tags, on a
nightly schedule and on manual dispatch (see [CI](#ci)).

### The two commands

There is one way to run each half of the suite.

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home  # Gradle 8.14 needs ≤ 21

./gradlew test     # no stack: unit tests, Postgres IT and the coverage gates
e2e/run.sh         # the devnet: boots the stack, e2eTest, the TS SDK suite, tears down
```

`./gradlew test` needs nothing but Docker (for the Postgres IT). It includes the
two coverage gates, so a missing mapping fails *before* anyone boots a devnet:

- `ErrorCodeCoverageTest` reads `ErrorCodes.java` as source and merges
  `e2e/coverage/wp-*.yml`. A **new error code fails the build** until it is
  asserted by a named test or excluded with a reason.
- `TransferMethodCoverageTest` reads `e2e/coverage/transfer-methods.yml`: every
  advertised `assetTransferMethod` needs a Java e2e test that settles a payment
  and a TS SDK test name that exists in `e2e/ts/test/*.mjs`. A stale entry fails too.

The gates re-run whenever `e2e/coverage`, `src/e2eTest/java` or `e2e/ts/test` change.

`e2e/run.sh` takes these arguments; everything else is a Gradle filter for `e2eTest`.

| Invocation | What it does |
|---|---|
| `e2e/run.sh` | Boot the stack, run the whole Java suite, then the TS suite, tear down |
| `e2e/run.sh --tests '*RejectionMatrixE2ETest'` | Any other arguments go to `./gradlew e2eTest`, so this is one class. The TS suite is skipped when there are filters |
| `e2e/run.sh --up` | Boot the stack and keep it; run nothing |
| `e2e/run.sh --keep` | As the plain run, but leave the stack running afterwards |
| `e2e/run.sh --reuse` | If both facilitators are already ready, skip preflight, build and boot, and never tear down; otherwise boot one and keep it |

The loop while developing a test: `e2e/run.sh --up` once, then
`e2e/run.sh --reuse --tests '*ShapeMatrixE2ETest'` as often as you like. The tests
only read the coordinates `run.sh` exports, so running Gradle by hand fails with
"is not set — run the suite through e2e/run.sh". Stop a kept stack with
`docker compose -p x402-e2e -f e2e/compose.e2e.yml down -v`.

Environment:

| Variable | Effect |
|---|---|
| `E2E_SKIP_BUILD=1` | Do not build the facilitator and DevKit images (CI builds them with its layer cache; locally use it when the images are current). Without it `run.sh` always rebuilds, cheaply from cache |
| `E2E_LOAD_SCALE=pr\|nightly` | Size of `LoadE2ETest` (default `pr`); see [Load](#load-e2e_load_scale) |
| `E2E_CANARY=1` | Build the newest DevKit (the resolved version is passed as a build-arg) and install the newest `@x402/cardano`, `@x402/core` and `@evolution-sdk/evolution` before the TS suite; prints the versions in use. `run.sh` resolves and builds the newest DevKit only when `E2E_SKIP_BUILD` is not `1`, so `E2E_CANARY=1 E2E_SKIP_BUILD=1` tests the pinned DevKit with the newest SDKs |
| `E2E_IMAGE`, `E2E_COMPOSE_PROJECT` | Facilitator image tag (default `x402-facilitator:e2e`) and Compose project (`x402-e2e`) |
| `E2E_YANO_HTTP_PORT`, `E2E_ADMIN_PORT`, `E2E_SHIM_PORT`, `E2E_FACILITATOR_PORT`, `E2E_FACILITATOR_MEMPOOL_PORT` | Host ports, default 6060 / 10000 / 16061 / 4022 / 4023. If one is taken the preflight stops with a message |

Requirements: Docker, Java 21, Node 22, python3, curl. Container logs land in
`e2e/.out/logs/`, JUnit XML in `e2e/.out/junit/java/` (Java) and
`e2e/.out/junit/ts.xml` (TS), the load summary in `e2e/.out/load/`. If a fresh
DevKit dies while booting, `run.sh` fails within seconds and names the cause: the
DevKit process tree is killed either by an out-of-memory condition or by a stale
`yaci-cli.pid` (fixed in `e2e/devnet/entrypoint.sh`); a plain retry is safe.

Timings (measured locally, not yet in CI): a full fresh run with
`E2E_SKIP_BUILD=1` takes about 10 minutes, of which the Java suite is the
bulk (176 Java tests, 6 TS tests; the latest full fresh run, measured locally on 2026-11-05/06). Test classes run in parallel; the
Java suite alone took 6 min 25 s sequentially and about 2 min 25 s once the kit
parallelised it (measured locally on 2026-11-05/06, before the chaos, fault and load classes were added).
`LoadE2ETest` at `pr` scale adds about 80 s (`BUILD SUCCESSFUL in 1m 20s` with `--reuse`), a nightly-scale run about 7.5 min (7m 27s).

### The stack

| Service | What | Why |
|---|---|---|
| `devkit` | npm `@bloxbean/yaci-devkit` 0.12.0, `yano-only`, magic 2 | The ledger. Components are baked into the image (`e2e/devnet/prefetch.sh`) |
| `shim` | `e2e/shim/yano_genesis_shim.py` in front of Yano | Blockfrost compatibility, a submit counter and opt-in [faults](#the-shim-fault-api) |
| `postgres` | two databases | One journal per facilitator instance |
| `facilitator` | the image, defaults | `accept-mempool=false` |
| `facilitator-mempool` | the image, `X402_SETTLE_ACCEPTMEMPOOL=true`, `X402_HTTP_CORSALLOWEDORIGINS=https://allowed.e2e.test` | The `l1Confirmations: -1` → `mempool` path, and the CORS tests |

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
| `CorsE2ETest` | The configured origin may `POST /verify`, `POST /settle` and `GET /supported`; another origin and the default instance get no allow-origin header |
| `DefaultTransferE2ETest` | ADA, native token, aux metadata, overpay — verify, settle, Yano inclusion, one broadcast |
| `ScriptTransferE2ETest`, `MasumiTransferE2ETest` | Script (V2, V3, parametrized, hash-only) and Masumi (ADA, token) locks on-chain, plus every method-specific rejection |
| `MasumiTermsConflictE2ETest` | The same Masumi terms in a second transaction → `duplicate_settlement`, nothing broadcast |
| `SettlementSemanticsE2ETest` | `l1Confirmations` 0/1/3/10/-1 per instance, idempotent re-settle, `duplicate_settlement`, spent nonce, a real concurrent-settle race |
| `RejectionMatrixE2ETest` | 39 single-defect cases: a valid control, then exactly one code on `/verify` **and** `/settle`, nothing broadcast |
| `ShapeMatrixE2ETest` | 11 transaction shapes: five that must settle (second input as nonce, enterprise payer, payTo output with another token, datum-hash script lock, uppercase nonce) and six that must be refused (stake withdrawal, registration certificate, mint, reference input, native-script witness → `phase1_invalid`; payTo split in two → `amount_insufficient`) |
| `e2e/ts` (node:test) | The **published** `@x402/cardano`/`@x402/core` 2.26.0: resource server issues, reference signer pays, `HTTPFacilitatorClient` settles — default ADA/token, script, Masumi ADA/token |

The `@Isolated` classes (see [below](#isolated-classes-disruptive-tests)) cover what a healthy devnet cannot produce:

| Class | Covers |
|---|---|
| `ShimFaultsE2ETest` | The shim itself: byte-for-byte pass-through with nothing registered, each fault action, `times`, latency, the 429 rate limit, malformed registrations refused, `/sdk/*` never faulted |
| `ProviderFaultE2ETest` | A lost submit answer (pending, then the landed tx confirms, no rebroadcast); a ledger rejection (definitive, and stays so on re-settle); a swallowed submit (pending, never rebroadcast); a UTxO without a lovelace entry (`input_value_unavailable`); a confirmed tx that disappears (pending, confirmed again when it reappears); a rate-limited lookup (`chain_lookup_failed`) |
| `MempoolEvidenceE2ETest` | A tx visible only in the mempool does not answer an `l1Confirmations: -1` request on the default instance; it waits for inclusion |
| `ChainOutageE2ETest` | Shim paused: 503 health, readiness DOWN, `chain_lookup_failed`, 503 fresh settle, journaled retry → `settlement_pending`; then recovery |
| `CrashRecoveryE2ETest` | A facilitator killed mid-settle resumes from the journal without a second broadcast; Postgres stopped fails closed (no broadcast, no success, readiness DOWN within 45 s) and recovers |
| `LoadE2ETest` | Settle mix, verify burst and duplicates under concurrency; see [Load](#load-e2e_load_scale) |

### How a case works

Every devnet transaction case is a `Case` row in a Java table (`kit/Case.java`).
There is no other case format. A row is a name, an optional defect and what the
facilitator must answer; the test method runs the same steps for every row:

1. **Take** a pre-funded single-UTxO wallet: `Funding.wallet(ada)` (or `Funding.wallets(n, ada)`).
2. **Build** the base payment with `PaymentFactory.Spec`: `customize(Tx)` adds intents,
   `preBalance` sets body fields QuickTx's `Tx` lacks, `nonceInput` chooses which input is the nonce.
3. **Break it**, if the row has a defect: a transaction mutation (`payments.mutateAndResign`),
   a payload or requirement edit (`withPayload`, `withRequirement`, `withExtra`), or a body/raw-JSON edit.
4. **Wrap** it in the x402 envelope, call `/verify`, then `/settle`.
5. **Assert** (`TransferAssert.assertCase`): the expected result (`Case.SETTLED` or the exact code), the
   response matches the JSON schema, the tx is on chain by its locally computed hash (read from Yano
   directly), and the shim's submit count for that hash is exactly 0 or 1.

Expected codes are string **literals** in the row, not constants: `ErrorCodeCoverageTest` checks the
source of the referencing class for each code's value.

**A positive shape** (`ShapeMatrixE2ETest`): `Case.SETTLED` with the shape as the base, so the control
that `/verify` accepts is the payment that then settles.

```java
Case.expect(Case.SETTLED, "enterprise-address payer")
        .base(w -> payments.from(Fixtures.enterpriseWallet(kit, 20)).payTo(receiver).lovelace(2_000_000).build()),
```

**A rejection** (`RejectionMatrixE2ETest`): a code and one defect. `row(...)` is the class's helper for
`Case.expect(code, name).defect(defect)`; the base is the standard 2 ADA payment and the control must verify
valid before the defect is applied.

```java
row("invalid_exact_cardano_payload_fee_below_minimum", "fee below the minimum",
        (p, w) -> payments.mutateAndResign(p, tx -> shiftFee(tx, w, -20_000))),
shared("invalid_exact_cardano_payload_amount_insufficient", "amount above what is paid",
        (p, w) -> p.withRequirement("amount", "2000001"))
```

`shared(...)` is for a defect that only edits the request: it starts from the one shared base payment and
spends nothing. A row that mutates or spends gets its own wallet.

**A Masumi row** (`MasumiTransferE2ETest`): the defect edits a `MasumiLock` builder before it signs
(`preSign`), after it signs (`postSign`) or edits the transaction (`txEdit`).

```java
row("invalid_exact_cardano_payload_masumi_deadline", "the lock is paid after payByTime",
        k -> k.payByFromNowMs = -10 * MINUTE_MS),
row("invalid_exact_cardano_requirements_masumi_deployment", "no deployment on a network without a canonical one",
        k -> k.postSign = extra -> extra.remove("deployment")),
```

Each Masumi lock needs unique terms (they can be claimed once); the allowlisted and non-allowlisted
deployments come from `MasumiLock.deployments()`. When you add a row, run its class with `--reuse`; when you
add an error code, map it in `e2e/coverage/` or exclude it with a reason, or `./gradlew test` fails.

The current exclusions, with reasons in `e2e/coverage/wp-4.yml`:

- unreachable over HTTP: `UNSUPPORTED_VERSION` (the registry answers 500 first)
- not producible deterministically: `SETTLEMENT_FAILED` (see the file for why)
- dead (never returned by main): `SETTLEMENT_NOT_CONFIRMED`, `SUBMISSION_MODE_MISMATCH`, `SUBMISSION_MODE_UNSUPPORTED`, `EVIDENCE_MISSING`, `CLIENT_SCRIPT_EXECUTION`

### `@Isolated` classes: disruptive tests

Classes run in parallel (`src/e2eTest/resources/junit-platform.properties`: classes concurrent, methods
sequential). A class that breaks the stack on purpose, by registering shim faults or latency, pausing the
shim, killing a facilitator, stopping Postgres or flooding it with load, is marked
`@org.junit.jupiter.api.parallel.Isolated`. JUnit then runs **nothing alongside it**: it starts after
the running classes finish and the others wait until it ends. The current set: `ShimFaultsE2ETest`,
`ProviderFaultE2ETest`, `MempoolEvidenceE2ETest`, `ChainOutageE2ETest`, `CrashRecoveryE2ETest`, `LoadE2ETest`.

`IsolatedStackGuard` is registered automatically (`META-INF/services` plus
`junit.jupiter.extensions.autodetection.enabled`). After every `@Isolated` class, while the class still holds
its isolation, it deletes every shim fault and the shim profile, then waits until **both** facilitators report
readiness UP. The next class therefore never starts against a half-recovered stack. A disruptive test should
still restore what it changed in `finally`; the guard is the backstop, not the plan.

Mark a new class `@Isolated` whenever it uses a shim fault with a broad match, a profile, `Stack.stop/kill`,
or anything else other classes could observe. Tests that only use their own wallets, hashes and receivers
need nothing.

### The shim fault API

With nothing registered, every request through the shim is a byte-for-byte pass-through. The control API is
handled by the shim and never forwarded; it applies to the facilitator's view only, `/sdk/*` is never faulted.
`ShimClient` (`kit/ShimClient.java`) wraps it for Java: `registerFault`, `deleteFault`, `clearFaults`,
`setProfile`, `clearProfile`, `reset`.

```bash
# register: id, match (keys are ANDed), action, optional times
curl -sS -X POST http://127.0.0.1:16061/__shim/faults -H 'content-type: application/json' \
  -d '{"id":"hide","match":{"txHash":"<64 hex>"},"action":{"type":"hide-tx"},"times":3}'
curl -sS -X DELETE http://127.0.0.1:16061/__shim/faults/hide    # one fault
curl -sS -X DELETE http://127.0.0.1:16061/__shim/faults         # all faults

# profile: latency for every request; a rate limit (token bucket) only when ratePerSec is given
curl -sS -X POST http://127.0.0.1:16061/__shim/profile -H 'content-type: application/json' \
  -d '{"latencyMs":150,"ratePerSec":10,"burst":500}'
curl -sS -X DELETE http://127.0.0.1:16061/__shim/profile

# how many times a submit body with this SHA-256 was broadcast
curl -sS 'http://127.0.0.1:16061/__shim/submits?sha256=<hex>'
```

| `match` key | Matches |
|---|---|
| `txHash` | `*/txs/<hash>[/…]` and `*/mempool/<hash>` |
| `sha256` | `POST */tx/submit` with a body of that SHA-256 |
| `outRef` (`<hash>#<index>`) | `*/txs/<hash>/utxos` (the URL has no index, so a `status` fault there hits every output of that tx; `drop-lovelace` alone is index-precise) |
| `pathRegex` | searched in the request path |

| `action.type` | Effect |
|---|---|
| `drop-response` | Forward the submit, answer `502` (the outcome the facilitator cannot know) |
| `swallow` | Do not forward, answer `200` with `action.txHash` |
| `ledger-reject` | Do not forward, answer `400` with `action.body` |
| `status` | Answer `action.status` (and optional `action.body`), no forward |
| `mempool` | `*/mempool/<hash>` answers `200` |
| `hide-tx` | `*/txs/<hash>` and its sub-paths answer `404`; `*/mempool/<hash>` is untouched |
| `drop-lovelace` | Remove the `lovelace` entry of output `outRef` from the `*/txs/<hash>/utxos` response |

`times` limits how often a fault fires. The profile's rate limit answers `429` with a Blockfrost-shaped body;
`POST */tx/submit` is always exempt (a 429 there would be an Unknown outcome), and the submit counter counts
every body received, faulted or not. A malformed registration is refused with `400`. The full contract is the
docstring of `e2e/shim/yano_genesis_shim.py`.

### Load (`E2E_LOAD_SCALE`)

`LoadE2ETest` (`@Isolated`) checks settlement invariants under concurrency and reports latency. Only the
invariants fail the test: every `success: true` hash is on chain (Yano, direct); no hash is broadcast more
than once; a re-sent settled payment never broadcasts again; every on-chain hash reaches `CONFIRMED` after
one reconciler sweep. Latency, the answer-code distribution and readiness flaps are **reported**, never
asserted, in `e2e/.out/load/summary.md` and `summary.json` (CI uploads them as the `e2e-reports-<label>` artifact).

| `E2E_LOAD_SCALE` | Settle mix | Verify burst concurrency | Used by |
|---|---|---|---|
| `pr` (default) | 20 payments | 1, 8, 32 | every run |
| `nightly` | 300 payments | 1, 4, 8, 16, 32, 64, 128 | the scheduled `nightly` leg |

The mix is weighted `SETTLED` rows: default ADA, default native token, Masumi ADA and token locks, and the five
positive shape rows. The verify burst runs against a slow, rate-limited shim (`latencyMs` 150, `ratePerSec` 10), so
at nightly scale most of the large bursts answer `chain_lookup_failed`; that is the fail-closed path being
exercised and is reported, not a failure. Any other value than `pr` or `nightly` fails the run.

```bash
E2E_SKIP_BUILD=1 E2E_LOAD_SCALE=nightly e2e/run.sh --tests '*LoadE2ETest'
```

The nightly scale funds about 300 wallets from one faucet top-up and takes about 7.5 minutes locally.

### Canary (`E2E_CANARY`)

`E2E_CANARY=1` runs the suite against the newest DevKit and the newest `@x402/cardano`, `@x402/core` and
`@evolution-sdk/evolution` (the pinned versions are in `e2e/devnet/Dockerfile` and
`e2e/ts/package-lock.json`). It exists to learn about upstream breakage before Renovate's pull request does. A red
canary is a signal to look, not to merge. Run it locally with `E2E_CANARY=1 e2e/run.sh` (it needs network access to npm). The newest DevKit is built only when `E2E_SKIP_BUILD` is not `1`: `E2E_CANARY=1 E2E_SKIP_BUILD=1 e2e/run.sh` tests the pinned DevKit with the newest SDKs. CI builds the canary DevKit itself, in the workflow's "Resolve the newest DevKit (canary)" and "Build devkit image" steps.

### CI

There is **one** workflow, `.github/workflows/build.yml` (workflow name `Build`), with three jobs:

| Job | Runs | When |
|---|---|---|
| `verify` | `./gradlew test interop --rerun-tasks` (unit, Postgres IT, the coverage gates, TS interop) and the Compose network-render check | every trigger except `schedule` (pull requests, pushes, tags, manual dispatch) |
| `e2e` | builds both images with the gha layer cache, then `e2e/run.sh`; check name `E2E (devnet, <label>)` | every trigger (the legs differ, below) |
| `publish` | the image and Maven artifacts | only after **both** `verify` and `e2e` are green, on a push to `main` or a tag; never on a pull request, `schedule` or manual dispatch |

Triggers: `pull_request`, pushes to `main`, `develop`, `release/*` and version tags (not other branches), a nightly
`schedule` (03:00 UTC) and `workflow_dispatch`. What runs where:

- **Pull request / push:** `verify` and one `e2e` leg, `E2E (devnet, pr)`, at `E2E_LOAD_SCALE=pr`.
- **`schedule`:** `verify` is skipped (only `schedule` skips it) and `e2e` runs two legs, `E2E (devnet, nightly)`
  at `E2E_LOAD_SCALE=nightly` (60 min timeout) and `E2E (devnet, canary)` with `E2E_CANARY=1` at load scale `pr`.
  There is no `pr` leg on a schedule, and it never publishes.
- **`workflow_dispatch`:** by default `verify` plus the `pr` leg. With the `nightly` input ticked, `verify` plus the
  `nightly` and `canary` legs (and no `pr` leg). A dispatch never publishes.
- A failing nightly or canary leg shows as a red `Build` run on `main`; nobody is blocked by it.
- Only `publish` sees the registry secrets. The `e2e` job runs third-party code (the canary installs `@latest`
  SDKs) and gets none.
- Every `e2e` leg uploads `e2e-reports-<label>`: `e2e/.out/` (JUnit XML, container logs, `networks.yml`, the load
  summary) and Gradle's test reports.

Make `verify` and `E2E (devnet, pr)` required checks in branch protection once the workflow has a record of green
runs; that is a repository setting, not part of this change.

### Known gaps

- **Yano is not cardano-node.** "Accepted by Yano" is weaker evidence than the
  real ledger. Yano answers a malformed submit with its own `Mempool admission failed`
  text, not a cardano-node `ApplyTxError`, so a ledger rejection is exercised through
  the shim's `ledger-reject` fault with a **synthetic** Blockfrost-wrapped body, never
  with text captured from a real node. Swap in a captured body if you have one.
- **Yano never reports a pending tx on `/mempool/{hash}`** on its own. The
  mempool-evidence branches are reached with the shim's `mempool` and `hide-tx` faults
  (`MempoolEvidenceE2ETest`), not by the chain.
- **A swallowed submit never expires.** A submission the provider accepted but that never
  reaches a block stays `settlement_pending` for good: Blockfrost-compatible providers give no
  watermark, so expiry can never be proven (`InclusionResult.java:9`). `ProviderFaultE2ETest`
  pins "never rebroadcast", not an expiry. This is an open finding.
- **Pinned as-is, not designed:** wrong method / content type / path return
  `500 internal_error` (the `ApiErrorAdvice` catch-all swallows Spring's
  405/415/404), and a 500 body's `correlationId` differs from the
  `X-Correlation-Id` header. `RoutesE2ETest` pins the first so a change is
  deliberate.
- `docs/api.md` describes `401`/`429` filters; none exist in the code, so
  nothing tests them.
- The full-stack Compose profile and any real cardano-node ledger are not covered
  (see [What isn't covered](#what-isnt-covered)).

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
BLOCKFROST_PROJECT_ID=preprod... E2E_MNEMONIC="..." ./gradlew preprodE2e
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
- **Mainnet and preprod in CI.** CI runs against the devnet only (the `schedule` legs are still the devnet); the preprod
  proof (`./gradlew preprodE2e`) is manual. Nothing here has run against mainnet.

## Before committing

Per the repo workflow: run the real suite and read the output — a green claim
without it is worthless.

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test
```

Expect `BUILD SUCCESSFUL` with no test failures.

For the complete upgrade gate, run `./gradlew clean test bootJar interop` after
installing the pinned interop dependencies.
