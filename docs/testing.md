# Testing

Four layers, in increasing order of what they prove:

| Layer | Command | Needs | Proves |
|---|---|---|---|
| Unit + slice | `./gradlew test` | nothing | Rules, wire shapes, state machine |
| Postgres IT | `./gradlew test` | Docker | Real CAS/claim semantics |
| TypeScript HTTP interop | `./gradlew interop` | Node + `npm ci --prefix interop` | Published SDKs accept Java HTTP responses and signed payments |
| **On-chain E2E** | `./gradlew e2e` | funded wallet + running facilitator | It actually works |

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

### Devnet

The harness is network-configurable; point `E2E_NETWORK` and `E2E_BACKEND_URL` at
a [Yaci DevKit](https://github.com/bloxbean/yaci-devkit) devnet to run the same
flow without faucet funds or preprod latency.

## What isn't covered

- **The full self-hosted stack.** The `full` Compose profile (Mithril +
  cardano-node + yaci-store) needs a live, synced node, which CI doesn't have,
  so it isn't exercised end-to-end. The facilitator-side code path is the same
  `BlockfrostChainService` already proven by the on-chain E2E test against
  hosted Blockfrost — only the full stack's integration is unproven.
- **Mainnet.** Nothing here has run against mainnet.

## Before committing

Per the repo workflow: run the real suite and read the output — a green claim
without it is worthless.

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test
```

Expect `BUILD SUCCESSFUL` with no test failures.

For the complete upgrade gate, run `./gradlew clean test bootJar interop` after
installing the pinned interop dependencies.
