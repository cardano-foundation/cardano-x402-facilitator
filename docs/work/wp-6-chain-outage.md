# WP-6: A chain outage degrades and recovers as specified
Tier: R1
Base: 97add9f
Check: `e2e/run.sh --java` green (the outage pass runs last via `@Tag("outage")`)

## Allowed files
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/ChainOutageE2ETest.java
- e2e/coverage/wp-6.yml

## Rules
- Annotate the class with `@Tag("outage")`. WP-1's run.sh runs it in a separate, final Gradle invocation.
- Setup before the outage: settle one payment to `confirmed` and keep its payload.
- Pause the shim with `docker compose -p $E2E_COMPOSE_PROJECT -f e2e/compose.e2e.yml pause shim`. Always unpause in finally/@AfterAll.
- First poll `/health` until it returns 503. `health()` caches for 5 s, and paused calls hang until the client timeout, so deadlines must exceed the provider timeout. A re-settle during the outage can make up to three blocking provider calls (inclusion, nonce lookup, params refresh), so the HTTP client deadline must be at least 3× the provider timeout.
- Then assert:
  - readiness DOWN, liveness UP
  - verify → EXACT_CARDANO_FACILITATOR_CHAIN_LOOKUP_FAILED
  - fresh settle → 503 with the same code
  - re-settle of the confirmed payload → 200 `settlement_pending` (the gate lets journaled retries through, SettlementGate.java:39; evidence is null, SettlementService.java:161-162)
- After unpause: readiness UP within the deadline, and a new payment settles `confirmed`.

## Proof commands
- red-first: n/a (characterization)
- green: as Check
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-6-chain-outage.md

## Log
- 2026-10-27 red-first: n/a (characterization).
- 2026-10-27 green: `e2e/run.sh --keep --java -PincludeTags=outage` exit 0 in 134 s; ChainOutageE2ETest PASSED (Gradle 1m17s), first run.
- 2026-10-27 Beyond the Rules: shim submit counts prove the journaled payment was never rebroadcast (1) and the refused one never broadcast (0); after recovery the journaled payment confirms again. The fresh payment gets ttlSlots(550) so it is still valid after the outage.
- 2026-10-27 Coverage: wp-6.yml maps CHAIN_LOOKUP_FAILED and SETTLEMENT_PENDING.
