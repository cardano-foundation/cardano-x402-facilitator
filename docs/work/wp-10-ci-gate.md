# WP-10: CI is one workflow — publishing waits for a green E2E run, and the schedule runs nightly scale and canary
Tier: R2
Base: 8b481e2
Check: `.github/workflows/` contains only `build.yml`; `actionlint` clean; `publish.needs == [verify, e2e]`; real proof is your next push (the Build run shows `verify` and `e2e`; a red e2e skips publish)

## Allowed files
- .github/workflows/build.yml
- .github/workflows/e2e.yml
- renovate.json
- docs/work/wp-10-ci-gate.md

## Rules
- **Delete `e2e.yml`**: its job moves into `build.yml`.
- **`build.yml`**:
  - Triggers as today, plus `schedule` (03:00 UTC).
  - Top-level concurrency deduplicates pull requests only: `group: build-${{ github.event_name == 'pull_request' && github.ref || github.run_id }}`, `cancel-in-progress: ${{ github.event_name == 'pull_request' }}`. A shared group for pushes and the schedule would let a scheduled run cancel a pending push to main, and with it that push's publish (GitHub keeps one running and one pending run per group).
  - `verify`: as today, `./gradlew test interop --rerun-tasks`. The coverage gates are part of `test` since WP-9/12. Skipped on `schedule`.
  - `e2e`, parallel to `verify`:
    - An explicit `name:` (e.g. `E2E (devnet)`), so check names stay stable for branch protection later.
    - Steps from today's `e2e.yml`: build both images with the gha cache, then `E2E_SKIP_BUILD=1 e2e/run.sh`.
    - `timeout-minutes: 45` on PR/push legs, 60 on the nightly-scale leg (whole suite plus up to 15 min of load).
    - Upload `e2e/.out/` and `build/test-results/e2eTest/`.
    - On `schedule`: a matrix (via `fromJSON`) of `{scale: nightly}` and `{canary: true}`, setting `E2E_LOAD_SCALE=nightly` or `E2E_CANARY=1`. Otherwise one leg with defaults (PR scale).
    - Canary leg only: a step resolves `npm view @bloxbean/yaci-devkit version`, and the devkit build step passes `YACI_DEVKIT_VERSION=<it>` as a build-arg. Other legs pass **no** build-args at all, because an empty `YACI_DEVKIT_VERSION=` would override the Dockerfile default.
  - `publish: needs: [verify, e2e]`, keeping its existing `if:` (push to main or a tag). Scheduled runs never publish.
  - Through Build, E2E now also runs on tags, `develop` and `release/*`.
  - No secrets are added; the e2e job uses none.
- **`renovate.json`**:
  - Managers: gradle, npm, dockerfile, docker-compose, github-actions; weekly schedule.
  - Group `@x402/*` + `@evolution-sdk/*` across `interop/` and `e2e/ts/`, so the SDK pins move together.
  - Group `com.bloxbean.cardano:*`.
  - A `customManagers` regex for `ARG YACI_DEVKIT_VERSION=` in `e2e/devnet/Dockerfile` (npm datasource `@bloxbean/yaci-devkit`).
  - Do not auto-merge.
- Not here: branch protection (making the check required). That's a GitHub setting for you, after the flake rate is measured over at least 10 runs.

## Proof commands
- red-first: n/a (CI config)
- `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest -color` → no findings
- `python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in sys.argv[1:]]" .github/workflows/*.yml` → parses
- `npx --yes --package renovate -- renovate-config-validator renovate.json` → valid
- Pending your push: the Build workflow on the PR shows `verify` and `e2e`; recorded here when you report it
- scope: per-batch scope file

## Log
- 2026-11-04 Ruling (orchestrator): WP-10 moved forward to run right after WP-9. WP-9 removed the run.sh modes and tags that the old e2e.yml used (`--java`, `java-main`/`java-outage` paths), so CI was broken until this package. WP-10 needs nothing from later packages: `E2E_LOAD_SCALE` is only an env var, and the coverage gates already run in `test`.
- 2026-11-04 Changed:
  - `.github/workflows/build.yml` rewritten:
    - triggers + `schedule` 03:00 UTC;
    - top-level concurrency deduplicates PRs only (`build-${{ github.event_name == 'pull_request' && github.ref || github.run_id }}`);
    - `verify` skipped on schedule;
    - inline `e2e` job named `E2E (devnet, <label>)`, with a `fromJSON` matrix: PR/push → `pr`; schedule → `nightly` + `canary`;
    - `E2E_SKIP_BUILD=1`, `E2E_LOAD_SCALE`, `E2E_CANARY` from the matrix;
    - timeout 45, or 60 on nightly;
    - the canary leg resolves `npm view @bloxbean/yaci-devkit version` and passes it as the only build-arg; other legs pass none;
    - artifacts `e2e-reports-<label>`;
    - `publish.needs: [verify, e2e]` with its `if` unchanged.
  - `.github/workflows/e2e.yml` deleted.
  - `renovate.json` added.
- 2026-11-04 actionlint (`rhysd/actionlint:latest`, `-verbose`): "Linting .github/workflows/build.yml … Found total 0 errors"; exit 0. `.github/workflows/` contains only `build.yml`.
- 2026-11-04 YAML parse via python: skipped, because PyYAML is not installed; actionlint parses the file and found 0 parse errors.
- 2026-11-04 Renovate validator:
  - `renovate@latest` (44.211.0): "Config validated successfully", with no migration warning, using `managerFilePatterns`.
  - The npx-cached 37.440.7 rejects `managerFilePatterns`, and 44.x migrates `fileMatch` away, so the current form was kept, since the hosted app runs the latest release.
- Pending: your push. The Build run should show `verify`, `E2E (devnet, pr)` and `publish` gated on both.
- 2026-11-04 Review (code-reviewer): no Critical. Fix round 1 by the orchestrator:
  - (Important) Moved the registry-secret `env` from workflow level into the `publish` job. The e2e job, which runs the canary's unpinned `@latest` SDK code, and `verify` no longer receive `GITLAB_PKG_REGISTRY_TOKEN`. `grep secrets.` now hits only `publish` (build.yml:157-175).
  - (Important) Canary DevKit resolve: the assignment form plus a non-empty guard emits `::error::` and fails, instead of silently building the pinned 0.12.0.
  - (Important) DevKit gha cache scope split: `devkit-pinned` vs `devkit-canary`, so the canary can't evict the PR cache.
  - (Minor) `workflow_dispatch` input `nightly` (boolean) runs the scheduled legs manually.
  - (Minor) Renovate `gradle-wrapper` manager enabled.
  - (Minor) Deviation recorded: the CCL group matches `com.bloxbean.cardano:cardano-client-**`, deliberately leaving out `aiken-java-binding`, which versions on its own.
  - Docs notes for WP-19: docs/testing.md:81-88 (workflow name and old flags); nightly/canary failures show as red `Build` runs on main.
  - Note: `E2E_LOAD_SCALE` is read by nothing until WP-18, so a green nightly isn't a load result yet.
  - Re-run: actionlint "Found total 0 errors"; renovate 44.211.0 "Config validated successfully".
- 2026-11-04 scope: `~/.claude/bin/check-scope.sh <scratchpad>/scope-union.md` (union of the WP-9 and WP-10 Allowed files plus plan and work docs) → `in scope: 23 changed file(s) match 11 glob(s)`, exit 0.
- 2026-11-04 Scoped re-review: Important 1-3 and Minors 2-6 ADDRESSED. The only remaining item was this scope line. No new breakage. The `run.sh:2` header pointing at the deleted e2e.yml is fixed in WP-9 fix round 1. **WP-10 complete** (pending: your push for the live CI proof).
