# WP-9: Each environment has one command and the stack has one Gradle task; every run tests fresh images, and a dead stack fails fast
Tier: R1
Base: 8b481e2
Check: `./gradlew test` runs `ErrorCodeCoverageTest` with no stack; `e2e/run.sh` green with exactly one devnet task `e2eTest`; a killed DevKit fails `run.sh` within 60 s

## Allowed files
- build.gradle
- e2e/run.sh
- .dockerignore
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/ErrorCodeCoverageTest.java
- src/test/java/org/cardanofoundation/x402/facilitator/e2e/ErrorCodeCoverageTest.java
- src/e2eTest/java/org/cardanofoundation/x402/facilitator/e2e/ChainOutageE2ETest.java
- docs/work/wp-9-run-and-gradle-tasks.md

## Rules
- **Gates move to `./gradlew test`:** move `ErrorCodeCoverageTest` from `src/e2eTest` to `src/test`, same package, **content unchanged**. It reads only files (`ErrorCodes.java`, `e2e/coverage`, `src/e2eTest/java`) relative to `user.dir`. `SchemaAssertSelfTest` stays in `e2eTest`, because it tests a kit class.
- **Make the gates rerun when their files change.** On the `test` task, declare each of `e2e/coverage`, `src/e2eTest/java` and `e2e/ts/test` as an **optional** input:
  - for example `inputs.dir('e2e/coverage').withPropertyName('coverageFragments').withPathSensitivity(PathSensitivity.RELATIVE).optional()`.
  - Without them, a change to only YAML or e2e sources leaves `test` UP-TO-DATE and the gate never runs locally. (CI uses `--rerun-tasks` and is unaffected.)
  - They must be optional because the image build runs the `test` task (`Dockerfile:14-15`) with a context that excludes `e2e/` and, after this package, `src/e2eTest/**`. A required missing input directory fails task validation and would break the image build.
- **One devnet task:** `e2eTest` (JUnit Platform, `outputs.upToDateWhen { false }`, default `build/test-results/e2eTest`).
  - No tag filters.
  - Remove the `-PincludeTags`, `-PexcludeTags` and `-Pe2eResultsDir` handling.
  - Disruptive classes are kept apart with JUnit's `@Isolated`, not with passes.
- **`ChainOutageE2ETest`:** swap `@Tag("outage")` for `@Isolated` (`org.junit.jupiter.api.parallel.Isolated`), and update its class Javadoc (`:36-38`, "Tagged outage … run.sh runs it alone, last") to say it is isolated. This is a scheduling and comment change only; no assertion changes. It's harmless until WP-11 enables parallel classes, and required after.
- Rename the preprod `JavaExec` task `e2e` → `preprodE2e` (only the name). Log the README/docs mentions for WP-19; do not edit docs here.
- **run.sh: one flow.** Boot → `./gradlew e2eTest` → TS `npm test` → teardown.
  - Flags:
    - `--up`: boot and keep, run nothing.
    - `--keep`: leave the stack running.
    - `--reuse`: if both facilitators' readiness is UP, skip preflight, build and boot, and never tear down. Otherwise boot normally and keep.
  - Any other arguments are Gradle filters for `e2eTest`; when they're given, the TS suite is skipped.
  - No modes, and no bash tag logic.
  - Images: always `docker build` the facilitator image and `compose build devkit` (layer cache keeps it cheap), unless `E2E_SKIP_BUILD=1` (CI sets it after building with the gha cache).
  - `E2E_CANARY=1`:
    - SDK: after `run_ts`'s `npm ci` (it deletes `node_modules`), `npm install --no-save @x402/cardano@latest @x402/core@latest @evolution-sdk/evolution@latest` in `e2e/ts`, then print the versions read from the installed `node_modules/*/package.json`.
    - DevKit: resolve a concrete version first (`npm view @bloxbean/yaci-devkit version`) and build with `--build-arg YACI_DEVKIT_VERSION=<that version>`. The constant string `latest` would hit the cached `npm install` layer forever. When `E2E_SKIP_BUILD=1`, the caller (CI) has built DevKit and must have passed the build-arg.
    - Print the DevKit version that actually runs, read from the container.
  - Fail fast in `wait_for`: for `devkit`, fail at once when the container is `exited`, its health is `unhealthy`, or `/tmp/devkit.log` contains `bootstrap failed`. For each facilitator, fail when its container is `exited`. Print the tail as today.
  - Copy `build/test-results/e2eTest/` into `e2e/.out/junit/java/` in `cleanup`, so CI's artifact path keeps working.
  - Keep the header usage block accurate.
- `.dockerignore`: exclude `src/e2eTest/**`. The image build runs only `src/test` classes; check the Dockerfile before excluding anything else.

## Proof commands
- red-first: no new tests (one test moved unchanged, one annotation swapped)
- `bash -n e2e/run.sh`
- `docker build --platform linux/amd64 .` succeeds with the new `.dockerignore` and the optional `test` inputs (run.sh builds it anyway; record that the build's `test` step passed)
- `./gradlew test` with no stack → green; the report lists `ErrorCodeCoverageTest` (7 tests)
- Rerun on file change: after a green `./gradlew test`, append a temporary `# probe` comment line to `e2e/coverage/wp-4.yml` (fingerprints are content-based, so `touch` alone proves nothing) → the next `./gradlew test` executes rather than reporting UP-TO-DATE; then remove the line
- `./gradlew tasks --all | grep -i e2e` → `e2eTest`, `preprodE2e` only
- `e2e/run.sh` → green; record wall time and test counts (Java 142 − 7 moved = 135 in `e2eTest`; TS 6)
- `e2e/run.sh --reuse --tests '*RoutesE2ETest'` against a kept stack → green without a reboot, TS skipped
- Fail-fast: `e2e/run.sh --up`; once it prints the devkit wait line (after the image builds), run `docker compose -p x402-e2e -f e2e/compose.e2e.yml kill devkit` → run.sh exits non-zero within 60 s of the kill
- Fresh image: two consecutive runs log `building facilitator image`; the second build step is cache-only (record its duration)
- `E2E_CANARY=1 e2e/run.sh --up` boots and prints the resolved SDK and DevKit versions
- scope: per-batch scope file (see plan, "How the work is executed")

## Log

### 2026-11-04 implementer (WP-9)
Changed:
- `build.gradle`: `test` gets three gate inputs (`e2e/coverage`, `src/e2eTest/java`, `e2e/ts/test`); `e2eTest` loses tag/results-dir handling (plain `useJUnitPlatform()`, `upToDateWhen{false}`); `e2e` JavaExec renamed `preprodE2e`.
- `e2e/run.sh`: rewritten as one flow (boot, `e2eTest`, TS, teardown); flags `--up/--keep/--reuse`, other args = Gradle filters (TS skipped); always builds both images unless `E2E_SKIP_BUILD=1`; `E2E_CANARY=1`; fail-fast watchers in `wait_for`; copies JUnit XML to `e2e/.out/junit/java/` in `cleanup`; header updated.
- `.dockerignore`: `src/e2eTest/**` excluded.
- `ErrorCodeCoverageTest`: `git mv` to `src/test/...` (content unchanged).
- `ChainOutageE2ETest`: `@Tag("outage")` -> `@Isolated`, import and class Javadoc updated.

Deviation: the brief's `inputs.dir(...).optional()` FAILS the Docker build (first `docker build` died with "input file does not exist": `.optional()` does not tolerate a missing directory for `inputs.dir`). Used `inputs.files(fileTree('<dir>')).withPropertyName(..).withPathSensitivity(RELATIVE)` instead: a fileTree over a missing dir is empty. Also added `stack_dead` (devkit + facilitators watch) while waiting for facilitators, beyond the brief.

Proofs:
- `bash -n e2e/run.sh` -> OK.
- `docker build --platform linux/amd64 .` -> succeeded; `[build 7/7] ... test ... bootJar` "BUILD SUCCESSFUL in 3m 1s" (4:55 wall, cold gradle layer). The `inputs.dir().optional()` variant failed it.
- `./gradlew test` (no stack) -> BUILD SUCCESSFUL; ErrorCodeCoverageTest XML `tests="7"`.
- Rerun on change: `test` UP-TO-DATE on 2nd run; after appending `# probe` to `e2e/coverage/wp-4.yml` -> `:test` executed (12 s); line removed, `git diff e2e/coverage` empty.
- `./gradlew tasks --all | grep -i e2e` -> `e2eTest`, `preprodE2e` (plus Gradle's auto `e2eTestClasses`, `compileE2eTestJava`, `processE2eTestResources`).
- `e2e/run.sh` -> exit 0, "e2e: PASSED", wall 8m18s (images cache-hit); Java 136 tests, 0 failed, 0 skipped (brief expected 135: see concern); TS `tests 6 pass 6 fail 0`; JUnit XML copied to `e2e/.out/junit/java/`.
- `e2e/run.sh --up` then `--reuse --tests '*RoutesE2ETest'` -> exit 0, "reusing the running stack", no boot, 29 RoutesE2ETest results PASSED, TS skipped, stack left running (12.9 s).
- Fail-fast: `--up`, `compose kill devkit` 3 s after the "starting devkit" line -> run.sh exit 1 one second later: `FAIL: devkit failed to boot: container exited`.
- Fresh image: two consecutive `--up` runs both log "building facilitator image" (exit 0 each); cache-only `docker build` = 1.034 s real.
- `E2E_CANARY=1 e2e/run.sh --up` -> exit 0: "canary: building DevKit 0.12.0", "canary: DevKit running: 0.12.0". SDK versions print only in `run_ts` (not on `--up`); checked the install line by hand: @x402/cardano 2.29.0, @x402/core 2.29.0, @evolution-sdk/evolution 0.8.0 (then `npm ci` restored).
- x402-e2e stack torn down at the end (no x402 containers). Other projects' containers untouched.

For WP-19 (docs mentions to update, not grepped exhaustively): README/docs references to `e2e/run.sh --java/--smoke/--ts`, `-PincludeTags/-PexcludeTags/-Pe2eResultsDir`, the `outage` tag, `./gradlew e2e` -> `preprodE2e`; `.github/workflows/e2e.yml` (outside allowed files) likely uses the old flags and the `e2e/.out/junit/java-main`/`java-outage` paths.

### Fix round 1 (WP-9, e2e/run.sh only)
1. Canary SDK versions at boot: after the "stack is up" line (outside the BOOT block) E2E_CANARY=1 prints `canary: DevKit running`, and `canary: newest <pkg>` from `npm view` for @x402/cardano, @x402/core, @evolution-sdk/evolution. The run_ts post-install print is unchanged.
2. JUnit copy: `JAVA_RAN` flag set in run_java; cleanup does `rm -rf $OUT/junit/java` then copies only when the Java suite ran in this invocation.
3. Header: points at .github/workflows/build.yml; E2E_LOAD_SCALE "is passed through to the Gradle test JVM (read by the load test)".
4. --reuse with a not-ready stack but existing containers: prints the `docker compose -p ... down -v` command to clear them (preflight would fail on ports).
5. DevKit version print moved out of the BOOT block, so it also prints under --reuse with no boot.

Proofs:
- `bash -n e2e/run.sh` -> SYNTAX_OK.
- `E2E_CANARY=1 e2e/run.sh --up` -> booted; printed `canary: building DevKit 0.12.0`, `canary: DevKit running: 0.12.0`, `canary: newest @x402/cardano 2.29.0`, `@x402/core 2.29.0`, `@evolution-sdk/evolution 0.8.0`; torn down.
- `e2e/run.sh --keep --tests '*RoutesE2ETest'` (exit 0) -> `ls e2e/.out/junit/java` = `binary`, `TEST-...RoutesE2ETest.xml`. Then `E2E_CANARY=1 e2e/run.sh --reuse --tests '*SchemaAssertSelfTest'` -> "reusing the running stack", canary lines printed (DevKit 0.12.0 + newest SDKs), PASSED; `ls e2e/.out/junit/java` = `binary`, `TEST-...kit.SchemaAssertSelfTest.xml` only (no stale XML). Torn down; no x402 containers left.
- Observation: one earlier attempt of the --keep run hung because DevKit's yaci-devkit process was `Killed` inside the container (log: "8 Killed yaci-devkit up"; OOMKilled=false, container still "running", health "starting" for 6+ min), so neither exited/unhealthy/'bootstrap failed' fast-fail triggered. A retry booted normally. Not fixed (out of the review's list); the fast-fail could additionally match "Killed" in /tmp/devkit.log if wanted. Those runs were aborted by me and the stack torn down.

### Fix round 2 (WP-9, e2e/run.sh only)
`devkit_dead` now also fails when /tmp/devkit.log contains `did not start within timeout` or the word `Killed` (grep -qw), reason: "'Killed' in devkit.log — likely out of memory; raise the Docker VM memory". Nothing else changed.
Proofs: `bash -n e2e/run.sh` OK. Grep logic on temp files: line "...8 Killed  yaci-devkit up" -> match; file with "Killing existing yaci-cli process" -> no match (so the normal "Killing" line does not trigger). `e2e/run.sh --tests '*RoutesE2ETest'` fresh boot -> exit 0, "e2e: PASSED", BUILD SUCCESSFUL; stack torn down, no x402 containers, no background processes of mine running.

### Fix round 3 (WP-9, e2e/run.sh only)
A. `devkit_dead` now greps `Killed` (-w) in the devkit CONTAINER log via `grep -qw 'Killed' < <(compose logs --no-color devkit 2>/dev/null)` (process substitution, no pipe); reason "'Killed' in the devkit container log — likely out of memory; raise the Docker VM memory". The 'did not start within timeout' check on /tmp/devkit.log is unchanged.
B. `rm -rf "$OUT/junit/java"` unconditionally right after `mkdir -p "$OUT"`; `run_java` does `rm -rf build/test-results/e2eTest` before Gradle; the JAVA_RAN-gated copy in cleanup is kept.

Proofs:
1. `bash -n e2e/run.sh` -> SYNTAX_OK.
2. Real fail-fast: `e2e/run.sh --up` in background; as soon as devkit was "running starting", `compose exec devkit pkill -9 -f 'yaci-cli|yaci-devkit up|yano'` (whole DevKit process tree, like an OOM kill; killing only the `yaci-devkit up` node wrapper does NOT produce the "Killed" report because sed/tee stay alive via yaci-cli, and DevKit then still boots healthy: two earlier attempts with that narrower kill passed through). Result: run.sh ended 2 s after the kill with `FAIL: devkit failed to boot: 'Killed' in the devkit container log — likely out of memory; raise the Docker VM memory`. Stack torn down.
3. `e2e/run.sh --keep --tests '*RoutesE2ETest'` (exit 0) -> junit/java had `binary` + RoutesE2ETest XML; then `e2e/run.sh --reuse --up` ("reusing the running stack", "--up: stack is running") -> `ls e2e/.out/junit/java`: No such file or directory. Torn down.
End state: no x402 containers, no run.sh/kill.sh processes of mine running.
