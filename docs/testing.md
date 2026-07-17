# Testing

Three layers, in increasing order of what they prove:

| Layer | Command | Needs | Proves |
|---|---|---|---|
| Unit + slice | `./gradlew test` | nothing | Rules, wire shapes, state machine |
| Postgres IT | `./gradlew test` | Docker | Real CAS/claim semantics |
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

144 tests, no network access, no credentials:

| Area | Class | # |
|---|---|---|
| Verification (A–E) | `ExactCardanoVerifyTest` | 32 |
| Masumi M1–M9 | `MasumiTransferVerifierTest` | 24 |
| Settlement | `ExactCardanoSettleTest` | 16 |
| Script method | `ScriptTransferVerifierTest` | 12 |
| yaci-store tri-state | `YaciStoreChainServiceTest` | 12 |
| Script address conformance | `ScriptAddressConformanceTest` | 9 |
| Controller | `FacilitatorControllerTest` | 6 |
| Wire shapes | `ProtocolJsonTest` | 6 |
| Decoder | `CardanoTransactionDecoderTest` | 6 |
| Guard filter | `ApiGuardFilterTest` | 6 |
| Settlement gate | `SettlementGateTest` | 5 |
| Networks | `CardanoNetworksTest` | 4 |
| Postgres IT | `SettlementPostgresIT` | 3 |
| Registry | `X402FacilitatorRegistryTest` | 2 |
| Context | `FacilitatorApplicationTest` | 1 |

`FakeChainService` (`testutil/`) drives every `UtxoState` / `SubmissionResult`
branch — including the `Unknown` paths, which are the ones you cannot reproduce
against a real backend on demand and are exactly where the dangerous bugs live.
`TestTx` builds transaction fixtures.

`ScriptAddressConformanceTest` pins aiken UPLC apply-params output byte-for-byte
against vectors taken from the reference (evolution-sdk), for both empty and
parametrized cases. If it fails after a dependency bump, the script method's
address reconstruction has silently changed — do not "fix" the vectors.

`SettlementPostgresIT` uses Testcontainers, so **Docker must be running**. It
exercises the CAS transitions against real Postgres; H2 won't catch the
concurrency semantics the reconciler depends on.

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

Configuration, all env-overridable:

| Var | Default | Notes |
|---|---|---|
| `BLOCKFROST_PROJECT_ID` | *(preprod test key in source)* | |
| `E2E_MNEMONIC` | *(preprod test wallet in source)* | Payer; needs funds |
| `E2E_BACKEND_URL` | preprod Blockfrost | Repoint for devnet |
| `FACILITATOR_URL` | `http://localhost:4022` | |
| `E2E_NETWORK` | `cardano:preprod` | |
| `E2E_AMOUNT_LOVELACE` | `1500000` | |

Payer is the mnemonic's address index 0, receiver is index 1, so the wallet pays
itself and only fees are consumed. Fund index 0 from the [preprod
faucet](https://docs.cardano.org/cardano-testnets/tools/faucet).

> **The committed defaults are throwaway preprod credentials.** They are testnet-only
> and were coded as overridable defaults for a one-off exercise — a documented
> deviation, not a pattern to copy. Never point this at mainnet, never commit a
> funded mnemonic, and rotate these before any non-test use. Always pass
> `E2E_MNEMONIC` / `BLOCKFROST_PROJECT_ID` explicitly.

### Reading the result

Success ends with:

```
ON-CHAIN CONFIRMED: tx <hash> in block <block>
  https://preprod.cardanoscan.io/transaction/<hash>
```

Two outcomes are **not** failures:

- `success: false` with `errorReason: exact_cardano_settlement_not_confirmed` —
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

- **The yaci-store live path.** Compile- and unit-verified only; exercising it
  needs a synced cardano-node, which CI doesn't have. Treat it as unproven
  end-to-end until you've run it against a real node.
- **Mainnet.** Nothing here has run against mainnet.

## Before committing

Per the repo workflow: run the real suite and read the output — a green claim
without it is worthless.

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew test
```

Expect `BUILD SUCCESSFUL`, 144 tests, 0 failures.
