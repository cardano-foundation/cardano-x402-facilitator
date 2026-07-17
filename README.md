# Cardano x402 Facilitator

An [x402](https://github.com/coinbase/x402) v2 facilitator for Cardano, in Java.

x402 revives HTTP `402 Payment Required` as a real payment flow: a resource
server quotes a price, a client pays, and the server serves. The **facilitator**
is the piece that answers two questions on the server's behalf — *is this payment
valid?* and *did it actually settle?* — so the resource server needs no chain
integration of its own.

This service implements the Cardano `exact` scheme, wire-compatible with the
[TypeScript reference](https://github.com/coinbase/x402/tree/main/typescript/packages/mechanisms/cardano).

**The facilitator never holds keys and never signs.** It verifies transactions
the payer already signed, and submits them. It cannot move funds on its own.

## Status

Implemented and unit-tested (144 tests, all green), proven end-to-end on
**preprod** with a real on-chain transaction. Nothing has run against mainnet —
see the [mainnet checklist](deploy/README.md#mainnet-readiness-checklist) before
you consider it. The yaci-store backend is compile- and unit-verified but its
live sync path has not been exercised against a synced node.

## Features

- **All three x402 Cardano transfer methods** — `default` (address-to-address),
  `masumi` (`vested_pay` escrow, rules M1–M9), and `script` (arbitrary Plutus
  locks with aiken UPLC parameter application).
- **Swappable chain backend** — Blockfrost, or a self-hosted yaci-store indexer
  against your own cardano-node. Chosen per network; no hybrid.
- **Settlement that survives reality** — journalled in Postgres, fenced CAS
  transitions, an asynchronous reconciler, and rollback detection. A tx that
  lands after the HTTP response, or a process that dies mid-submit, resolves
  correctly.
- **Tri-state UTxO resolution** — `Unspent` / `Spent` / `Unknown`. An indexer
  behind the tip returns a retryable error rather than a guess.
- **Wire-identical error codes** with the TS reference, plus two additive codes
  flagged for upstreaming.
- Java 21, Spring Boot 3.5, virtual threads, Log4j2, Prometheus metrics.

## Quickstart

Requires Java 21 and a PostgreSQL. With Docker:

```bash
cd deploy
BLOCKFROST_PROJECT_ID=preprod... docker compose --profile light up -d
```

Or run it directly:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
BLOCKFROST_PROJECT_ID=preprod... ./gradlew bootRun
```

Check it's serving:

```bash
curl localhost:4022/supported
# {"kinds":[{"x402Version":2,"scheme":"exact","network":"cardano:preprod"}],...}
```

Then prove it works against the real chain — build, sign, verify, settle, and
independently confirm on-chain:

```bash
BLOCKFROST_PROJECT_ID=preprod... ./gradlew e2e
```

See [docs/testing.md](docs/testing.md) — including how to point it at your own
wallet, which you should.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/verify` | Is this signed payment valid? |
| `POST` | `/settle` | Submit it and confirm it |
| `GET` | `/supported` | Which (version, scheme, network) triples are served |
| `GET` | `/health` | Human-facing summary |
| `GET` | `/actuator/health`, `/actuator/prometheus` | Probes and metrics |

```bash
curl -X POST localhost:4022/verify -H 'Content-Type: application/json' -d '{
  "x402Version": 2,
  "paymentPayload": {
    "x402Version": 2,
    "resource": { "url": "https://example.test/report" },
    "accepted": { "scheme": "exact", "network": "cardano:preprod",
                  "asset": "lovelace", "amount": "1500000", "payTo": "addr_test1..." },
    "payload": { "transaction": "<base64 signed tx CBOR>", "nonce": "<txHash>#0" }
  },
  "paymentRequirements": { "scheme": "exact", "network": "cardano:preprod",
                           "asset": "lovelace", "amount": "1500000", "payTo": "addr_test1...",
                           "maxTimeoutSeconds": 600,
                           "extra": { "assetTransferMethod": "default" } }
}'
```

Two things clients get wrong:

- **A rejected payment is `200 OK`** with `isValid: false`. Read the body, not
  the status.
- **`settle` failing with `exact_cardano_settlement_not_confirmed` and a
  non-empty `transaction` does not mean the payment failed.** It was broadcast
  and may still land. Poll that hash; do not re-submit.

Full contract and every error code: [docs/api.md](docs/api.md).

## Configuration

Everything lives under `x402` in `application.yml`. The minimum is one network
with a backend:

```yaml
x402:
  networks:
    - id: "cardano:preprod"
      chain:
        mode: blockfrost
        blockfrost:
          base-url: https://cardano-preprod.blockfrost.io/api/v0
          project-id: ${BLOCKFROST_PROJECT_ID}
```

API keys, rate limiting, and CORS are **off by default** — the service is open
unless you opt in. Full reference: [docs/configuration.md](docs/configuration.md).

## Documentation

| Doc | What's in it |
|---|---|
| [docs/api.md](docs/api.md) | Endpoints, request/response shapes, every error code |
| [docs/verification.md](docs/verification.md) | What `/verify` checks, in order, and why |
| [docs/architecture.md](docs/architecture.md) | Packages, chain SPI, settlement state machine, persistence |
| [docs/configuration.md](docs/configuration.md) | Every property, defaults, and the ones that matter |
| [docs/testing.md](docs/testing.md) | Unit suite and the on-chain E2E proof |
| [deploy/README.md](deploy/README.md) | Docker Compose, Mithril sync, mainnet checklist |

Design history: `docs/superpowers/specs/` (spec) and `docs/superpowers/plans/`
(implementation plan). Those are how it was *designed*; the docs above describe
what was *built*.

## Development

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home

./gradlew test        # 144 tests (Docker needed for the Postgres IT)
./gradlew bootRun     # run locally
./gradlew e2e         # on-chain proof; facilitator must be running
```

Stack: Java 21 · Spring Boot 3.5.16 · cardano-client-lib 0.7.2 ·
aiken-java-binding 0.1.0 · yaci-store 2.0.2 · PostgreSQL · Gradle 8.14.

Before changing chain code, read the tri-state UTxO and yaci-store sections of
[docs/architecture.md](docs/architecture.md) — several non-obvious behaviours
there (spent rows retained deliberately, an era-hardcoding workaround, fail-open
tip freshness) are load-bearing and easy to "simplify" into bugs.

## Security

- The facilitator holds **no keys** and signs nothing.
- `/verify` and `/settle` are **unauthenticated** unless you set
  `x402.security.api-keys`. Put it behind TLS.
- The `masumi` script-hash allowlist is **inactive until configured**; without it
  the escrow method trusts the address in the requirements. Set it before
  mainnet.
- The E2E test class carries **throwaway preprod credentials** as overridable
  defaults — testnet-only, a documented deviation, and to be rotated. Never point
  it at mainnet.
