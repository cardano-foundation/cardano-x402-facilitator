# WP-8: CI runs the whole e2e suite on every PR and push to main
Tier: R1
Base: 97add9f
Check: full `e2e.yml` green on a PR from the feature branch (the user pushes; Claude never writes to GitHub)

## Allowed files
- .github/workflows/e2e.yml
- docs/testing.md
- README.md

## Rules
- Extend WP-1's smoke workflow.
- Triggers: pull_request (opened, synchronize, reopened), push to branches [main], workflow_dispatch.
- Settings: `permissions: contents: read`; no secrets; `concurrency: e2e-${{ github.ref }}` with cancel-in-progress for PRs only; `timeout-minutes: 30`.
- Steps:
  1. checkout
  2. setup-java 21 (gradle cache)
  3. setup-node 22
  4. docker buildx build with `cache-from/to: type=gha`, loaded as `E2E_IMAGE`
  5. `e2e/run.sh`
  6. always(): upload JUnit XML and the facilitator, facilitator-mempool, devnet and shim logs
- Pin actions to the major tags build.yml already uses, plus docker/setup-buildx-action, docker/build-push-action and actions/upload-artifact@v4.
- docs/testing.md:
  - Add a "Devnet E2E" layer row and section: how to run it, the shim and why it exists, the two facilitator instances, the exclusions list, and the known gaps (Yano as ledger, the Rejected regex, the docs/api.md 401/429 filters, the 500 catch-all).
  - Replace the stale "What isn't covered" claims that the new layer now covers.
- README: update the Status line.
- If wall time exceeds 25 min, split Java and TS into two jobs that share the image via an artifact. Report back first.

## Proof commands
- red-first: n/a
- green: actionlint .github/workflows/e2e.yml (if installed), plus a CI run on the PR
- scope: ~/.claude/bin/check-scope.sh docs/work/wp-8-workflow-and-docs.md

## Log
- 2026-10-27 Full workflow written (setup-java 21 with Gradle cache, setup-node 22 with npm cache on e2e/ts/package-lock.json, buildx + gha cache for both images, `e2e/run.sh`, always-upload of e2e/.out + the Gradle HTML report). actionlint is not installed locally: the PR run is the check.
- 2026-10-27 Supporting infra: run.sh always dumps the container logs (tails print only on failure); each Java pass writes its own JUnit dir via -Pe2eResultsDir (build.gradle), and the TS suite writes e2e/.out/junit/ts.xml.
- 2026-10-27 Local full cold run (`e2e/run.sh`, macOS arm64, amd64 emulated): exit 0 in 505 s. TS 6/6; Java main 142/142; outage 1/1; all JUnit + logs present in e2e/.out. Native x64 CI should be faster; well under the 30-min timeout, so no job split.
- 2026-10-27 regress: `./gradlew test --rerun-tasks` 365 tests, 0 failures.
- 2026-10-27 docs/testing.md: Devnet E2E layer row + section (running, stack, shim and why, coverage table, exclusions, known gaps); 'What isn't covered' updated. README Status updated. One earlier finding withdrawn: docs/api.md already documents the two 413 body shapes as intended.
- 2026-10-27 code-reviewer: `e2e/run.sh` full mode runs both suites and fails at the end, so a TS red no longer skips the Java suite (and `|| return` per step, since set -e is off inside a function called with ||). Docs: the Needs column adds python3/curl; the JUnit location is described per mode.
