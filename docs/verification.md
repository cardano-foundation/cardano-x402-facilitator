# Verification Rules

What `POST /verify` actually checks, in the order it checks it. Settlement runs
the same pipeline before it submits anything, so everything here applies to
`/settle` too.

Implementation: `service/verification/ExactCardanoScheme.java`, plus one verifier
per `assetTransferMethod` under `service/verification/method/`.

## Design constraints worth knowing

- **The facilitator never signs.** It receives an already-signed transaction and
  decides whether to accept it. There is no key material in this service.
- **Checks are ordered cheapest-first.** Envelope and decode are pure CPU; chain
  I/O only happens once the payload is structurally credible. A malformed
  payload never costs a Blockfrost call.
- **Every rejection is a `200 OK` with `isValid: false`.** See [api.md](api.md).

## Pipeline overview

| Stage | Does | Chain I/O |
|---|---|---|
| A | Envelope: version, scheme, network, payload shape, requirement sanity | no |
| B | Decode CBOR, network id, signatures | no |
| C | Validity window and protocol limits | yes |
| D | Replay protection and payer authorization | yes |
| E | Value transfer + per-method dispatch | yes (protocol params) |

---

## Stage A — envelope

No I/O. Ordered:

1. `paymentPayload.x402Version != 2` → `..._unsupported_version`
2. `accepted` missing, or `accepted.scheme`/`paymentRequirements.scheme` ≠ `"exact"` → `unsupported_scheme`
3. `normalize(accepted.network)` ≠ `normalize(requirements.network)` → `network_mismatch`
4. `requirements.network` not a supported Cardano network → `network_mismatch`
5. `payload.transaction` or `payload.nonce` missing/empty → `invalid_exact_cardano_payload`
6. `nonce` not matching `^[0-9a-fA-F]{64}#\d+$` → `..._nonce_invalid`
7. **A4** base64 length implies more than `x402.verification.max-tx-bytes` decoded → `invalid_exact_cardano_payload`

   Estimated from the base64 length (`len * 3 / 4`) *before* decoding, so an
   oversized body is rejected without allocating it.
8. **A5** requirement sanity — all → `invalid_exact_cardano_payload`:
   - `amount` not an integer string (`"amount is not an integer string"`)
   - `amount` ≤ 0 (`"amount must be positive"`)
   - `asset` neither `lovelace` nor `^[0-9a-fA-F]{56}\.[0-9a-fA-F]{0,64}$` with an
     even-length name part
   - `payTo` not a Shelley address whose bech32 prefix matches the network
     (`addr` on mainnet, `addr_test` otherwise)

A5 exists because the requirements come from the *resource server*, not the
payer. A typo'd `payTo` or a negative `amount` would otherwise fail deep in the
pipeline with a misleading payer-blaming error.

## Stage B — decode

9. CBOR won't decode → `..._transaction_decode_failed`
10. Tx network id present and ≠ the expected id (mainnet 1, testnets 0) → `..._network_id_mismatch`
11. No vkey witnesses **and** no script witnesses → `..._unsigned`
12. Signature check fails → `..._invalid_signature`

## Stage C — time and protocol limits

13. If the tx carries a validity interval, fetch the current slot:
    - lookup fails → `exact_cardano_facilitator_chain_lookup_failed`
    - `ttlSlot <= currentSlot` → `..._ttl_expired`
    - `validityStartSlot > currentSlot` → `..._not_yet_valid`
14. Protocol params fetch fails → `exact_cardano_facilitator_chain_lookup_failed`
15. **C3** serialized size > `min(max-tx-bytes, protocol maxTxSize)` → `invalid_exact_cardano_payload`

    A4 bounds work before decoding; C3 re-checks the true size against the
    *live protocol limit*, so a tx that could never be accepted by a node is
    rejected here rather than at submission.

## Stage D — replay protection

This is the part that stops a payment being spent twice, so it's worth reading
closely.

16. `nonce` is not among the tx's inputs → `..._nonce_not_in_inputs`
17. Any UTxO lookup throws → `exact_cardano_facilitator_chain_lookup_failed`
18. Any input resolves to `Unknown` → `exact_cardano_facilitator_chain_lookup_failed`
    (`"input state unknown (indexer sync horizon)"`)
19. The nonce UTxO is not `Unspent` → `..._nonce_not_on_chain`
20. Any other input is `Spent` → `..._input_not_available`
21. **D5** payer is not authorized → `..._payer_not_witness`

**UTxO state is tri-state — `Unspent`, `Spent`, `Unknown` — and the third one
carries the safety property.** An indexer that hasn't caught up cannot
distinguish "never existed" from "not indexed yet". Collapsing `Unknown` into
either answer is a real bug in both directions: into `Spent` and you reject
honest payments; into `Unspent` and you accept replays. So `Unknown` is neither
— it's a retryable `chain_lookup_failed`. **An error is never absence.**

**D5** (`checkPayerAuthorization`) closes an authorization gap: without it, an
attacker could name someone else as the payer. It rejects when the payer address
is unparseable or Byron, has an empty payment credential, is a key credential
absent from the verified witness key hashes, or is a script credential with no
script witness.

## Stage E — value transfer and method dispatch

Iterates the outputs and takes the first that fully covers the amount. For that
output:

- Below the min-UTxO floor (`MinAdaCalculator`) → `..._min_utxo_insufficient`
- Dispatch to the method verifier (below); its error is returned verbatim
- Otherwise → `isValid: true` with the resolved payer

If no output matched, the failure is attributed by how far it got:

| Condition | Code |
|---|---|
| No output pays `payTo` | `..._recipient_mismatch` |
| Output exists but wrong asset | `..._asset_mismatch` |
| Right recipient and asset, too little | `..._amount_insufficient` |

Unexpected exceptions become `..._verification_error`; `ChainLookupException`
becomes `exact_cardano_facilitator_chain_lookup_failed`.

---

## Choosing an `assetTransferMethod`

```java
requirements.extra.assetTransferMethod   // "default" | "masumi" | "script"
```

Absent `extra`, or absent key → `"default"`. Unknown value → `unsupported_scheme`
(`"assetTransferMethod '<x>' is not supported by this facilitator"`).

**Read from `paymentRequirements.extra`, never `paymentPayload.accepted.extra`.**
The payload half is payer-supplied; letting it pick the method would let a payer
downgrade `masumi` escrow rules to `default`. The canonical requirements are the
only trustworthy source.

The verifier runs **only** for the output that already passed
address + asset + amount + min-UTxO. Method rules are additive on top of the
shared checks, never a replacement.

---

## `default`

No additional checks — address-to-address is fully covered by Stage E.

## `masumi` — `vested_pay` escrow

Verifies that funds are locked into the Masumi escrow contract correctly, rather
than merely sent to its address. Rules M1–M9, in order:

| # | Check | Code |
|---|---|---|
| M1 | `extra.contractAddress` present and ≡ `payTo` | `..._masumi_contract_mismatch` |
| M1b | `payTo`'s script hash is in the configured allowlist (if set) | `..._masumi_contract_mismatch` |
| M2 | An output to `payTo` carries an inline datum | `..._masumi_datum_missing` |
| — | That output carries **no** reference script | `..._masumi_reference_script` |
| — | Datum parses as a lock datum | `..._masumi_datum_invalid` |
| M3 | Structural invariants (below) | `..._masumi_datum_invalid` |
| M8 | Deadline (below) | `..._masumi_deadline` |
| M9 | Collateral bounds (below) | `..._masumi_collateral` |
| — | Exact asset set (below) | `..._masumi_asset` |
| M9 | Post-result min-UTxO | `..._masumi_min_utxo` |
| M7 | Declared `extra` fields match the datum | `..._masumi_datum_mismatch` |

**M3 structural invariants** — all must hold, else the datum is not a fresh lock:
state is `FundsLocked`; `resultHash` empty; both cooldown times zero; neither
buyer nor seller credential is a script; `referenceSignature` at least 16 bytes
(32 hex characters — the field is hex-encoded, so the check is on string length);
and `payByTime <= submitResultTime <= unlockTime <= externalDisputeUnlockTime`.

**M8 deadline** — the tx **must** carry a TTL (no TTL → `..._masumi_deadline`),
converted to POSIX ms via the network clock, and must satisfy
`ttlPosixMs <= payByTime`. A lock that could land after its own pay-by deadline
is not a valid lock, so the TTL is mandatory here even though it's optional in
Stage C.

**M9 collateral** — `collateral >= 0`; if non-zero it must be at least
`1_435_230` lovelace; and it must not exceed the output's coin.

**Asset set** — for lovelace, `output.coin >= amount + collateral` and no native
assets; for a token, the held amount must equal `amount` exactly and be the only
asset. `!=`, not `>=`: escrow locks an exact figure.

**M7 field matching** — three different behaviours, worth separating:

*Always checked:* buyer credentials against the payer address, and
`sellerAddress` (which must be present — an absent one is a mismatch).

*Checked only when declared* — omit and nothing is asserted: `referenceKey`,
`referenceSignature`, `sellerNonce`, `identifierFromPurchaser`, `agentIdentifier`,
`inputHash`, `payByTime`, `submitResultTime`, `unlockTime`,
`externalDisputeUnlockTime`, `collateralReturnLovelace`.

*Checked even when undeclared:* `sellerReturnAddress`. It is **not** optional in
the same sense. `returnAddressMatches` treats an omitted declaration as asserting
the datum carries **no** seller return address:

```java
if (declared == null) return actual == null;   // omitted ⇒ datum must be None
```

So omitting `sellerReturnAddress` while the datum sets one fails with
`..._masumi_datum_mismatch`. Omission is an assertion of absence, not a skip.

*Never checked:* `buyerReturnAddress`. It is buyer-supplied — the 402 answers an
unauthenticated request, so the resource server does not know the payer and
cannot declare its refund address. The buyer is pinned by the `buyer == payer`
assertion instead, so a declared `buyerReturnAddress` is ignored rather than
matched.

For the genuinely optional fields, an undeclared field is not an assertion, so
there is nothing to disagree with. Callers that want one enforced must declare
it.

> **M1b is off unless you configure it.** With no allowlist, M1 only proves the
> output went to the address the *requirements* named — a resource server naming
> a hostile address still passes. Set
> `x402.masumi.allowed-script-hashes.<network>` in production. This is why it
> appears on the
> [mainnet checklist](../deploy/README.md#mainnet-readiness-checklist).

## `script` — arbitrary Plutus lock

| # | Check | Code |
|---|---|---|
| S1 | `extra.scriptHash` or `extra.script` present, and the reconstructed script address ≡ `payTo` | `..._script_address_mismatch` |
| S2 | Asset/amount/min-UTxO | *(shared Stage E)* |
| S3 | Datum present per the Plutus version's policy | `..._script_datum_missing` |

**S1** reconstructs the address rather than trusting `payTo`. When `parameters`
are supplied, the script is applied via aiken's UPLC `apply_params` and hashed —
verified byte-for-byte against known-good vectors for both empty and
parametrized cases (see `ScriptAddressConformanceTest`). Parameter types:
`bytes`, `string`, `bigint`, `integer`, `boolean`.

**S3** varies by `extra.script.type`:

| Version | Requires |
|---|---|
| `plutusV1` | a datum **hash** (inline is not valid for V1) |
| `plutusV2` | inline **or** hash |
| `plutusV3` | inline or hash, unless `x402.verification.script-datum-policy: v3-optional` |
| absent / unknown (hash-only) | inline or hash |

V3 is configurable because the datum genuinely is optional for some V3 validators,
but requiring it is the safe default — a lock whose validator needs a datum and
didn't get one is unspendable, i.e. funds burned. Default is `strict`; opt out
per-deployment only if you know your validator tolerates it.

---

## Error code notes

Two codes exist beyond the core set — `..._payer_not_witness` and
`..._script_datum_missing` — covering the payer-authorization and
datum-presence checks described above.

One deliberate, documented edge case: an explicit JSON `null` on a field that
is otherwise optional is rejected rather than treated the same as an omitted
field. An omitted field is silently skipped; an explicit `null` is not an
equivalent input and fails with an invalid-payload error instead.

Coverage lives in `ExactCardanoVerifyTest` (32), `MasumiTransferVerifierTest`
(24), `ScriptTransferVerifierTest` (12), `ScriptAddressConformanceTest` (9), and
`CardanoTransactionDecoderTest` (6).
