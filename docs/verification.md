# Verification Rules

Compatibility target: `@x402/cardano` 2.26.0 at upstream main
`6323ec74c85607e706e0722dd294365a7fb57768`. See [upgrade notes](upstream-compatibility.md).

`POST /verify` is read-only. Fresh settlement uses the same verification before
claiming and submitting. Invalid payments return HTTP 200 with `isValid: false`;
transport and framework failures use the statuses in [api.md](api.md).

## Envelope, encodings and signatures

- Require x402 v2, the exact scheme and matching normalized Cardano networks.
- Require a transaction and an input out-reference nonce. The nonce must actually
  occur among the transaction inputs, and duplicate transaction inputs fail.
- Amounts are canonical positive decimal strings; assets are lovelace or a
  lowercase policy/name unit. Base64 must be canonical and padded as necessary.
- Enforce size limits before decoding and again against live protocol parameters;
  bound transaction input/output counts and script parameter work.
- Hash the original transaction body bytes. Verify every supplied vkey signature,
  required signer, and the nonce owner's payment credential.
- Reject an envelope carrying `is_valid: false` and inconsistent network tags.
- Confirmation policy is a closed object containing only integer
  `l1Confirmations` in `-1..20`. Omission means `1`; explicit null fails.

All fresh payments use facilitator submission. Legacy mode metadata cannot choose
an alternate verification or submission path. Masumi uses a closed schema and
rejects removed `submissionPolicy` and `terms.settlementPolicy` fields.

## Pre-broadcast ledger checks

Check the validity interval against the current slot and the requested timeout.
Resolve every input as `Unspent`, including its authenticated owner, coin and
native-asset quantities; unknown state or provider failure never means absence.

For ordinary transfers, require exact conservation of lovelace (outputs plus
fee) and every native asset, authorized input owners, and a fee at least
`minFeeCoefficient * serializedBytes + minFeeConstant`. Check every output's
network and min-UTxO, including change. Missing trustworthy input quantities fail
with `exact_cardano_facilitator_input_value_unavailable`.

The built-in validator handles ordinary transfers. Minting, withdrawals,
certificates, governance operations, reference/collateral inputs and script
execution require an operator-supplied `Phase1Validator` Spring bean that performs
the complete additional ledger validation. Without it, such bodies fail closed.
The hook receives immutable decoded data, input snapshots, protocol parameters
and the network. It runs for ordinary transfers too when installed.

## Durable retries

`verifyBroadcast` retains envelope, hash, signature, payer, network, nonce,
recipient, asset and method checks. It skips checks that only make sense before
broadcast, such as input availability, TTL freshness and input/output balance
using live UTxOs. Only settlement calls it after durable local provenance or
independently authenticated canonical evidence establishes a broadcast.

This permits a correctly submitted transaction to settle after consuming its own
inputs or passing its original TTL. It does not make standalone `/verify` accept
an arbitrary spent transaction. Legacy rows cannot establish acceptance merely
from their old status. Blockfrost receipts with `valid_contract: false` never
establish successful payment evidence.

## Transfer methods

The method comes from canonical `paymentRequirements.extra`, never from the
payer's echoed `accepted.extra`. Each method applies after the common recipient,
asset, amount and minimum-UTxO checks.

### `default`

At least one output must pay the requested asset and amount to the recipient.
The amount may exceed the requested minimum.

### `masumi`

The closed, bounded schema requires `inputCommitment`, signed `terms`, CIP-8
`referenceKey`/`referenceSignature`, and `blockchainIdentifier`.

Before inspecting the lock, recompute part digests, inputHash and termsDigest;
validate the seller's Ed25519 COSE authorization, header/key consistency and
address binding. JCS content explicitly equal to JSON null is hashed as null;
an omitted content field permits a precomputed digest. Raw content must be
canonical unpadded base64url. Identifier decoding is bounded and compares all
six components: seller nonce, agent identifier, buyer nonce, reference signature,
reference key and escrow address.

The derived escrow must match `payTo`. Canonical deployments are built in.
An explicit deployment additionally requires the operator's allowed script hash
or `MasumiDeploymentValidator` approval. If terms claim an `agentIdentifier`,
`MasumiRegistryValidator` must independently authenticate it for the network and
resource endpoint. Neither missing validator defaults to approval.

Require exactly one output to the escrow and an inline lock datum with no
reference script. Enforce fresh `FundsLocked` state, empty result/cooldowns,
key credentials, signed term matching, deadline order and transaction TTL before
payByTime. The buyer's payment key must match a valid payer witness; an unrelated
stake credential cannot stand in for that key.

For ADA, require the exact requested amount plus collateral, with no native
assets. For tokens, require the exact quantity of the sole requested token.
Collateral bounds and the post-result min-UTxO calculation apply to both.
Settlement atomically claims the seller-signed terms digest together with the
transaction hash so another transaction cannot consume the same quote.

### `script`

Reconstruct the script address from its hash or descriptor. Supplied hash and
script must agree. UPLC parameters use strict bounded scalar encodings for
bytes, string, bigint, integer and boolean; unsafe numeric values are rejected.
Aiken parameter application is pinned by conformance vectors.

`x402.verification.script-datum-policy` selects:

| Policy | Behavior |
|---|---|
| `reference` (default) | Upstream permissive datum presence, except V1 with inline datum is rejected |
| `strict` | V1 requires datum hash; V2/V3/hash-only require inline datum or hash |
| `v3-optional` | As strict, but V3 may omit datum |

These policies concern new outputs locked by scripts. Spending script inputs
still requires complete ledger validation through the phase-1 hook above.
