# Upstream compatibility and upgrade

This implementation targets `x402-foundation/x402` main commit
[`6323ec74c85607e706e0722dd294365a7fb57768`](https://github.com/x402-foundation/x402/tree/6323ec74c85607e706e0722dd294365a7fb57768),
including the published `@x402/cardano` and `@x402/core` **2.26.0** packages.
The exact Node dependency graph is pinned in `interop/package-lock.json`.

## Wire changes

| Previous behavior | Current behavior |
|---|---|
| Server/client submission modes | Facilitator submits fresh payments; journaled retries only reconcile |
| Per-mode confirmation ranges in `/supported` | One `l1Confirmations: {minimum, maximum}` range |
| `settlement_not_confirmed` after confirmation timeout | `settlement_pending`, transaction hash and `extra.transactionId` |
| Optional cached confirmed replay | Every retry checks current evidence, including after rollback |
| Process confirmation-depth fallback | Request policy, default one confirmation, persisted per transaction |
| 180-second confirmation wait | 75 seconds, below core's default 90-second HTTP timeout |
| Partial input snapshots | Owner, coin and native-asset quantities plus live fee parameters required |
| Loose Masumi metadata | Closed bounded schema, full commitments/identifier/CIP-8 authentication |

A confirmed absence after the transaction's TTL plus indexing grace permits the
upstream terminal `exact_cardano_settlement_failed` / `extra.status: "expired"`
response. Its transaction and terms claims remain reserved. Provider uncertainty
or a TTL-less transaction cannot establish that terminal result.

Default and script payments tolerate unknown top-level metadata as upstream
does; old submission fields have no operational effect. `confirmationPolicy`
admits only `l1Confirmations`. Masumi rejects removed fields, including
`submissionPolicy` and `terms.settlementPolicy`.

Repeated settlement success reports payment evidence. Resource servers must
persist whether a paid business operation has already been consumed. Their
Masumi issuance/consumption store remains separate from the facilitator's
transaction and quote claims.

## Database rollout

`V2__upstream_main_settlement.sql` adds selected confirmation depth, submission
provenance, authenticated acceptance and a unique Masumi terms digest. V1 is
unchanged. Transaction and terms claims reside in one row, so a conflicting
terms claim cannot leave an orphan transaction claim.

Historical records intentionally receive `LEGACY` provenance, null selected
depth and false acceptance. Their old status cannot prove a local broadcast:
the previous implementation also recorded client-submitted transactions.
Matching validated retries may establish policy and current canonical evidence;
old cached responses never establish success. No historical failed, expired or
possibly broadcast row is automatically eligible for resubmission.

Deploy the new version to all facilitator writers together. An older writer
does not populate the new provenance/policy/terms columns and cannot enforce the
new quote-claim invariant; mixed-version writers are unsupported. Preserve the
journal across restarts and upgrades. Restoring an old binary does not restore
its old semantics for rows produced by the new version.

## Operator extension points

- `Phase1Validator`: provide complete ledger validation for advanced transaction
  bodies (minting, withdrawals, certificates, governance or script execution).
  Ordinary balanced transfers work with the built-in validator. Unsupported
  bodies fail closed without the hook.
- `MasumiRegistryValidator`: independently authenticate a claimed registry agent
  and resource endpoint on the requested network. Registry-bearing requirements
  fail closed without this hook.
- `MasumiDeploymentValidator`: approve an explicitly supplied escrow deployment.
  A matching configured script-hash allowlist is an alternative approval. A
  custom trust domain is not implicitly accepted just because its address derives.

These are optional Spring beans; they are not remote callback URLs accepted
from payment requests. Existing constructors remain available for embedding.
Input snapshots and protocol parameter constructors without quantities/fees
remain source-compatible but fail closed during fresh verification.

`BlockfrostChainService` embedders must supply a `NetworkClock` and `Clock` to
compute validity slots. The old constructor remains available for chain I/O but
fails closed on current-slot queries. Application wiring supplies these clocks
automatically. Current slots advance with wall time even between blocks;
provider health and confirmation evidence continue to query Blockfrost.

`confirmation-depth` and `idempotent-replay` remain accepted configuration keys
for migration, but do not override the current wire policy or disable retry
reconciliation. Mempool acceptance still requires explicit operator opt-in and
`l1Confirmations: -1`.

## Deliberate implementation boundaries

The Java journal is durable across process restarts and fences competing workers;
upstream's default in-memory stores do not provide that durability. A transport
failure with an uncertain submit outcome returns protocol pending immediately
and retains the claim. The TypeScript implementation may initially report a
generic settlement failure for that uncertainty; both preserve the transaction
for later reconciliation.

Malformed Unicode in Masumi JCS values, keys and manifest strings is rejected
before UTF-8 encoding can replace it. The interoperability gate checks these
rejections against the TypeScript implementation's exact error codes.

The default script datum policy also refuses a Plutus V1 inline-datum lock.
Stricter operator datum policies remain available. Transaction verification does
not claim to replace full Cardano ledger validation for arbitrary advanced
transactions; those require the phase-1 hook described above.

## Reproducible checks

```bash
npm ci --prefix interop --ignore-scripts
./gradlew clean test bootJar interop
```

Use Java 21 and a running Docker daemon for the PostgreSQL tests. The interop
gate uses a real loopback Java HTTP server and the published TypeScript client
and resource server, with controlled chain evidence. Its signed ADA, token,
script and Masumi vectors are generated and independently verified by the
TypeScript implementation (`npm run fixtures --prefix interop`).

Container builds additionally run Masumi and script address derivation on
`linux/amd64`. The pinned Aiken binding does not bundle a Linux ARM64 native
library, so Compose selects x86-64 even on Apple Silicon. See the
[container platform and rebuild instructions](../deploy/README.md#compose-profiles).

The automatic checks do not broadcast on a live chain. The existing preprod E2E
driver is updated for pending responses and the current timeout limit; the
upgrade has not rerun that live proof. See [testing.md](testing.md).
