# API Reference

The facilitator implements the x402 v2 facilitator contract for the Cardano
`exact` scheme.

Base URL in the examples: `http://localhost:4022`.

## JSON conventions

Set globally in `config/JacksonConfig`, and they apply to every endpoint:

- **`null` fields are omitted** from responses (`NON_NULL` inclusion). A field
  documented as "null when …" is simply absent, not `"field": null`.
- **Unknown request fields are ignored** rather than rejected.
- Parser bounds (defence in depth under the byte cap): max nesting depth 64, max
  string length 200 000, max number length 1 000.

Amounts are **strings** holding integer lovelace (or the asset's smallest unit) —
never JSON numbers, so no precision is lost.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/verify` | optional API key | Validate a signed payment without touching the chain state |
| `POST` | `/settle` | optional API key | Submit the payment and wait for confirmation |
| `GET` | `/supported` | open | Advertise the (version, scheme, network) triples served |
| `GET` | `/health` | open | Human-facing summary |
| `GET` | `/actuator/health` | open | Machine probes (liveness/readiness) |
| `GET` | `/actuator/prometheus` | open | Metrics |

`/verify` and `/settle` are guarded only when `x402.security.api-keys` is
configured; see [configuration.md](configuration.md).

---

## `POST /verify`

Decides whether a signed transaction satisfies the stated payment requirements.
It is read-only with respect to the chain: nothing is submitted.

### Request

```json
{
  "x402Version": 2,
  "paymentPayload": {
    "x402Version": 2,
    "resource": { "url": "https://example.test/report", "description": "e2e" },
    "accepted": { "...": "same shape as paymentRequirements" },
    "payload": {
      "transaction": "<base64 CBOR of the signed tx>",
      "nonce": "<txHash>#<index>"
    }
  },
  "paymentRequirements": {
    "scheme": "exact",
    "network": "cardano:preprod",
    "asset": "lovelace",
    "amount": "1500000",
    "payTo": "addr_test1...",
    "maxTimeoutSeconds": 600,
    "extra": { "assetTransferMethod": "default" }
  }
}
```

**`paymentPayload`**

| Field | Type | Notes |
|---|---|---|
| `x402Version` | int | Must be `2`; anything else fails the registry lookup |
| `resource` | object | Opaque to the facilitator; echoed by the caller's own bookkeeping |
| `accepted` | object | The `paymentRequirements` the payer accepted |
| `payload.transaction` | string | Base64-encoded CBOR of the **signed** transaction |
| `payload.nonce` | string | `<txHash>#<index>` of an input the payer controls — the replay anchor |
| `extensions` | object | Optional |

**`paymentRequirements`**

| Field | Type | Notes |
|---|---|---|
| `scheme` | string | `exact` |
| `network` | string | `cardano:mainnet` \| `cardano:preprod` \| `cardano:preview`, or a CIP-34 alias (below) |
| `asset` | string | `lovelace`, or `<policyIdHex>.<assetNameHex>` for a native asset — **dot-separated**, 56 hex policy id, even-length asset name |
| `amount` | string | Positive integer, smallest unit |
| `payTo` | string | Bech32 recipient address |
| `maxTimeoutSeconds` | int | Payer-facing TTL budget |
| `extra` | object | Method selector + per-method fields — see [verification.md](verification.md) |

### Response — `200 OK`

Valid:
```json
{ "isValid": true, "payer": "addr_test1..." }
```

Invalid:
```json
{
  "isValid": false,
  "invalidReason": "invalid_exact_cardano_payload_amount_insufficient",
  "invalidMessage": "…human-readable detail…",
  "payer": ""
}
```

| Field | Type | Notes |
|---|---|---|
| `isValid` | bool | Always present |
| `invalidReason` | string | An [error code](#error-codes); omitted when valid |
| `invalidMessage` | string | Human-readable; omitted when valid |
| `payer` | string | Resolved payer address; `""` (not omitted) when invalid and unresolvable |

**A rejected payment is still `200 OK`.** `isValid: false` is a verdict, not a
transport error — clients must read the body, not just the status.

---

## `POST /settle`

Verifies, then submits the transaction and waits for it to reach
`x402.settle.confirmation-depth` blocks. Request body is identical to `/verify`.

### Response — `200 OK`

Settled:
```json
{
  "success": true,
  "payer": "addr_test1...",
  "transaction": "560c090d4fe4b0d6...",
  "network": "cardano:preprod",
  "extra": { "status": "confirmed" }
}
```

Failed:
```json
{
  "success": false,
  "errorReason": "exact_cardano_settlement_not_confirmed",
  "transaction": "560c090d4fe4b0d6...",
  "network": "cardano:preprod"
}
```

| Field | Type | Notes |
|---|---|---|
| `success` | bool | Always present |
| `errorReason` | string | An [error code](#error-codes); omitted on success |
| `errorMessage` | string | Human-readable; omitted on success and on tx-carrying failures |
| `payer` | string | Resolved payer; omitted when never resolved |
| `transaction` | string | **Always present.** The tx hash once submitted, `""` when nothing was submitted |
| `network` | string | Always present; echoes the request |
| `extra.status` | string | Settlement status when known |

`transaction` and `network` are non-null by contract, so `transaction: ""` —
not an absent field — is how "never submitted" is expressed.

**`success: false` does not mean the money didn't move.** A
`exact_cardano_settlement_not_confirmed` carrying a non-empty `transaction` means
the tx *was* broadcast and confirmation timed out. It may still land. Treat that
tx hash as authoritative and poll the chain — do not re-submit and do not assume
failure. The E2E proof does exactly this.

### `503 Service Unavailable`

When the chain backend for the requested network is unhealthy, `/settle` refuses
rather than accepting a payment it cannot confirm:

```json
{
  "success": false,
  "errorReason": "exact_cardano_facilitator_chain_lookup_failed",
  "errorMessage": "settlement backend unhealthy",
  "transaction": "",
  "network": "cardano:preprod"
}
```

This is retryable.

---

## `GET /supported`

```json
{
  "kinds": [ { "x402Version": 2, "scheme": "exact", "network": "cardano:preprod" } ],
  "extensions": [],
  "signers": { "cardano:*": [] }
}
```

One `kinds` entry per configured network, always with `x402Version: 2`. `signers`
carries one key per registered CAIP family — `cardano:*` — mapping to an empty
list, because the facilitator holds no signing keys. Clients should use this for
discovery rather than assuming a network is served.

## `GET /health`

```json
{
  "status": "ok",
  "networks": [ { "id": "cardano:preprod", "mode": "blockfrost", "required": true } ]
}
```

A human-facing summary. **Use `/actuator/health` for orchestration probes** —
it carries the real readiness/liveness state, including backend health.

---

## Networks

Canonical ids, plus CIP-34 aliases that `normalize()` folds onto them:

| Canonical | CIP-34 alias | Protocol network id |
|---|---|---|
| `cardano:mainnet` | `cip34:1-764824073` | 1 |
| `cardano:preprod` | `cip34:0-1` | 0 |
| `cardano:preview` | `cip34:0-2` | 0 |

Matching is **exact string equality** — no case folding, no trimming.
`CARDANO:PREPROD` is rejected.

---

## Error codes

Every value below is a stable code returned in `invalidReason` /
`errorReason`, except the two noted below as extensions to the core set.

### Envelope / routing

| Code | Meaning |
|---|---|
| `unsupported_scheme` | Scheme other than `exact` |
| `invalid_exact_cardano_payload` | Payload missing or structurally unusable |
| `invalid_exact_cardano_payload_unsupported_version` | `x402Version` not served |
| `network_mismatch` | Requirements/payload disagree on network |

### Transaction structure and authorization

| Code | Meaning |
|---|---|
| `invalid_exact_cardano_payload_transaction_decode_failed` | CBOR would not decode |
| `invalid_exact_cardano_payload_network_id_mismatch` | Tx network id ≠ requested network |
| `invalid_exact_cardano_payload_unsigned` | No witnesses |
| `invalid_exact_cardano_payload_invalid_signature` | Witness signature check failed |
| `invalid_exact_cardano_payload_payer_not_witness` | Payer address did not sign |

### Validity window

| Code | Meaning |
|---|---|
| `invalid_exact_cardano_payload_ttl_expired` | `validTo` already passed |
| `invalid_exact_cardano_payload_not_yet_valid` | `validFrom` in the future |

### Nonce / replay

| Code | Meaning |
|---|---|
| `invalid_exact_cardano_payload_nonce_invalid` | Nonce not `<txHash>#<index>` |
| `invalid_exact_cardano_payload_nonce_not_in_inputs` | Nonce UTxO isn't an input of this tx |
| `invalid_exact_cardano_payload_nonce_not_on_chain` | Nonce UTxO doesn't exist |
| `invalid_exact_cardano_payload_input_not_available` | Nonce UTxO already spent |

### Payment terms

| Code | Meaning |
|---|---|
| `invalid_exact_cardano_payload_recipient_mismatch` | No output to `payTo` |
| `invalid_exact_cardano_payload_asset_mismatch` | Wrong asset |
| `invalid_exact_cardano_payload_amount_insufficient` | Paid less than `amount` |
| `invalid_exact_cardano_payload_min_utxo_insufficient` | Output below the min-UTxO floor |
| `invalid_exact_cardano_payload_verification_error` | Verification raised unexpectedly |

### Chain / settlement

| Code | Meaning |
|---|---|
| `exact_cardano_facilitator_chain_lookup_failed` | Backend unreachable or degraded |
| `exact_cardano_settlement_failed` | Node rejected the submission |
| `exact_cardano_settlement_not_confirmed` | Submitted, confirmation timed out — **may still land** |
| `duplicate_settlement` | Same payment already claimed |

### `masumi` method

| Code | Meaning |
|---|---|
| `invalid_exact_cardano_payload_masumi_contract_mismatch` | Not the expected escrow address |
| `invalid_exact_cardano_payload_masumi_datum_missing` | No inline datum |
| `invalid_exact_cardano_payload_masumi_datum_invalid` | Datum not a well-formed lock datum |
| `invalid_exact_cardano_payload_masumi_datum_mismatch` | Datum field ≠ declared value |
| `invalid_exact_cardano_payload_masumi_deadline` | Deadline ordering violated |
| `invalid_exact_cardano_payload_masumi_collateral` | Collateral outside bounds |
| `invalid_exact_cardano_payload_masumi_min_utxo` | Post-result output below min-UTxO |
| `invalid_exact_cardano_payload_masumi_reference_script` | Reference script attached (forbidden) |
| `invalid_exact_cardano_payload_masumi_asset` | Locked asset set ≠ required set |

### `script` method

| Code | Meaning |
|---|---|
| `invalid_exact_cardano_payload_script_address_mismatch` | Reconstructed script address ≠ output |
| `invalid_exact_cardano_payload_script_datum_missing` | Datum absent under the version's policy |

Two codes — `..._payer_not_witness` and `..._script_datum_missing` — extend
the core set to close payer-authorization and datum-presence gaps. Treat any
unrecognized `invalid_exact_cardano_payload_*` code as a generic rejection.

---

## Transport-level errors

These come from the framework/filters, not the scheme, and use an
`{"error": ...}` body rather than the x402 envelope:

| Status | Body | Cause |
|---|---|---|
| `400` | `{"error": "Missing paymentPayload or paymentRequirements"}` | Absent required object |
| `400` | `{"error": "Malformed request body"}` | Unparseable JSON |
| `401` | Spring default error body | Missing/invalid `X-API-Key` (only when keys configured) |
| `413` | **Two shapes — see below** | Over `x402.http.max-request-bytes` |
| `429` | Spring default error body + `Retry-After: 60` | Over the rate limit (only when configured) |
| `500` | `{"error": "No facilitator registered for scheme: …"}` | Unregistered (version, scheme, network) |
| `500` | `{"error": "internal_error", "correlationId": "<uuid>"}` | Unhandled; detail is in the log under that id |

An unregistered triple is a `500` on purpose. It reads like a server bug and
arguably should be a 4xx, but the facilitator surfaces the failure as-is
rather than translating it into a client error.

Filters (`401`, `429`, and the pre-flight `413`) reject via `sendError`, which
never reaches the `@RestControllerAdvice` — the container renders Spring Boot's
default error JSON (`{"timestamp":…,"status":…,"error":…,"path":…}`) instead of
the `{"error": …}` shape. **Match on the status code, not the body.**

### The two `413` shapes

| Path | Trigger | Body |
|---|---|---|
| Pre-flight | `Content-Length` header exceeds the cap | Spring default error JSON |
| Mid-stream | Body exceeds the cap while being read (absent/lying `Content-Length`) | `{"error": "Request body too large"}` |

Both are `413`; only the mid-stream path travels as an exception through
`ApiErrorAdvice`. The two-path design is deliberate — a chunked or
`Content-Length`-spoofing client must still be bounded — but the body shape is an
artifact of *where* the limit trips, not a contract. Only the status code is
tested.

Every request carries an `X-Correlation-Id` (generated when absent, echoed back,
logged as `%X{correlationId}`). Quote it when reporting a `500`.
