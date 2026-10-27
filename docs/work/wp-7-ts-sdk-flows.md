# WP-7: The published TS SDK pays through the container for every transfer method
Tier: R1
Base: 97add9f
Check: `e2e/run.sh --ts` green

## Allowed files
- e2e/ts/**

## Rules
- A separate package; don't touch interop/. Pin `@x402/core` and `@x402/cardano` 2.26.0 and `@evolution-sdk/evolution` 0.5.9. Commit the lockfile and install with `npm ci --ignore-scripts`.
- First step: spike whether the 2.26.0 client can build live Masumi and script payments against a Blockfrost-style provider. If it can't, STOP and report back, since this affects plan decision 2. Do not fall back silently.
- Use node:test. For each method (default ADA, token, script, Masumi ADA, Masumi token):
  1. `x402ResourceServer` issues requirements.
  2. The `@x402/cardano` client builds and signs, with its provider pointed at Yano directly.
  3. `HTTPFacilitatorClient` runs verify and settle against `FACILITATOR_URL`.
  4. An independent lookup on Yano confirms the tx.
- Masumi uses the fixed deployment from `e2e/devnet/masumi-deployment.json` (read-only); it is already allowlisted.
- Also check that the SDK parses `/supported` from both facilitator instances.
- Fund wallets via the DevKit admin topup; mint the token with Evolution.

## Proof commands
- red-first: n/a (characterization)
- green: e2e/run.sh --ts
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-7-ts-sdk-flows.md

## Log
- 2026-10-27 Spike result: the published 2.26.0 client CAN build live default, script and Masumi payments; the container verifies and settles all of them on-chain. It was blocked only by Yano API differences that Evolution's Blockfrost provider validates strictly: utxos lack `tx_index`; params give drep_deposit/gov_action_deposit as numbers. Reported to the user, who chose: a separate shim view `/sdk/api/v1` that fixes both, with the facilitator's `/api/v1` view unchanged. Deviation from the Rule 'provider pointed at Yano directly': the SDK's provider uses `E2E_SDK_CHAIN_URL` (shim /sdk); independent on-chain checks still read Yano directly.
- 2026-10-27 Also found by the spike: the WP-1 Masumi deployment had 29-byte admin vkeys (58 hex). Fixed to 28 bytes; hashes recomputed with @x402/cardano masumiEscrowScriptHash and cross-checked with Java MasumiBlueprint (identical).
- 2026-10-27 Harness accommodation: the SDK's reference signer hardwires Evolution's shared `preview` chain object; the suite sets its slotConfig.zeroTime to the devnet genesis (the TS twin of the facilitator's NETWORKS_FILE slot-config).
- 2026-10-27 green (against a kept stack): 6/6 — /supported both instances, default ADA, default token (minted with Evolution, native-script policy), script V3, Masumi ADA, Masumi token.
- 2026-10-27 npm reports 3 high-severity advisories in the pinned dependency tree (test-only; versions are pinned by the plan).
- 2026-10-27 code-reviewer: the on-chain check now uses a LOCALLY computed hash (decodeCardanoTransaction(...).txHash), asserts it equals settled.transaction, and asserts settled.payer. engines raised to node >=22 (the test glob needs >=21).
