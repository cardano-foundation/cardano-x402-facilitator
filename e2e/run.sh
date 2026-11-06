#!/usr/bin/env bash
# One-command devnet e2e run — locally and in CI (.github/workflows/build.yml).
#
#   e2e/run.sh                      boot stack, ./gradlew e2eTest, TS suite, tear down
#   e2e/run.sh --tests '*FooTest'   any other args are Gradle filters for e2eTest; TS suite skipped
#   e2e/run.sh --up                 boot the stack and keep it; run nothing
#   e2e/run.sh --keep               leave the stack running afterwards
#   e2e/run.sh --reuse              if both facilitators are already ready, skip preflight, build and
#                                   boot, and never tear down; otherwise boot normally and keep
#
# Env: E2E_IMAGE (facilitator image), E2E_COMPOSE_PROJECT, E2E_{YANO_HTTP,ADMIN,SHIM,FACILITATOR,
# FACILITATOR_MEMPOOL}_PORT.
#   E2E_SKIP_BUILD=1  skip the facilitator and DevKit image builds (CI builds them with its cache)
#   E2E_CANARY=1      newest DevKit (resolved version as build-arg) and newest @x402/cardano,
#                     @x402/core, @evolution-sdk/evolution; prints the versions in use
#   E2E_LOAD_SCALE    is passed through to the Gradle test JVM (read by the load test)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
E2E="$ROOT/e2e"
OUT="$E2E/.out"

export E2E_IMAGE="${E2E_IMAGE:-x402-facilitator:e2e}"
export E2E_COMPOSE_PROJECT="${E2E_COMPOSE_PROJECT:-x402-e2e}"
export E2E_YANO_HTTP_PORT="${E2E_YANO_HTTP_PORT:-6060}"
export E2E_ADMIN_PORT="${E2E_ADMIN_PORT:-10000}"
export E2E_SHIM_PORT="${E2E_SHIM_PORT:-16061}"
export E2E_FACILITATOR_PORT="${E2E_FACILITATOR_PORT:-4022}"
export E2E_FACILITATOR_MEMPOOL_PORT="${E2E_FACILITATOR_MEMPOOL_PORT:-4023}"

# What the suites read.
export FACILITATOR_URL="http://127.0.0.1:$E2E_FACILITATOR_PORT"
export FACILITATOR_MEMPOOL_URL="http://127.0.0.1:$E2E_FACILITATOR_MEMPOOL_PORT"
export E2E_CHAIN_URL="http://127.0.0.1:$E2E_YANO_HTTP_PORT/api/v1"   # Yano direct, never via the shim
export E2E_SHIM_URL="http://127.0.0.1:$E2E_SHIM_PORT"
export E2E_SDK_CHAIN_URL="$E2E_SHIM_URL/sdk/api/v1"                 # the TS SDK's provider (see e2e/shim/)
export E2E_ADMIN_URL="http://127.0.0.1:$E2E_ADMIN_PORT"
export E2E_COMPOSE_FILE="$E2E/compose.e2e.yml"
export E2E_MASUMI_DEPLOYMENT="$E2E/devnet/masumi-deployment.json"

JAVA_RAN=0
UP_ONLY=0 KEEP=0 REUSE=0 GRADLE_ARGS=()
while (($#)); do
  case "$1" in
    --up) UP_ONLY=1 KEEP=1 ;;
    --keep) KEEP=1 ;;
    --reuse) REUSE=1 ;;
    *) GRADLE_ARGS+=("$1") ;;
  esac
  shift
done

compose() { docker compose -p "$E2E_COMPOSE_PROJECT" -f "$E2E_COMPOSE_FILE" "$@"; }
log() { printf '\n=== %s\n' "$*"; }
fail() { printf '\nFAIL: %s\n' "$*" >&2; exit 1; }

# Poll until a command succeeds or the deadline (seconds) passes. WATCH, when set,
# names a function that fails the run at once if what we wait for can no longer happen.
WATCH=
wait_for() {
  local deadline=$(($(date +%s) + $1)) what=$2; shift 2
  until "$@" >/dev/null 2>&1; do
    [[ -z "$WATCH" ]] || "$WATCH"
    (($(date +%s) < deadline)) || fail "timed out after waiting for $what"
    sleep 2
  done
}

port_free() { ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
# cstate <service> <inspect format>: state of a service's container, exited ones included.
cstate() { docker inspect -f "$2" "$(compose ps -aq "$1")" 2>/dev/null || true; }
devkit_healthy() { [[ "$(cstate devkit '{{.State.Health.Status}}')" == healthy ]]; }
ready() { curl -fsS "$1/actuator/health/readiness" | grep -q '"UP"'; }

devkit_dead() {
  local why=
  if [[ "$(cstate devkit '{{.State.Status}}')" == exited ]]; then why="container exited"
  elif [[ "$(cstate devkit '{{.State.Health.Status}}')" == unhealthy ]]; then why="container unhealthy"
  elif compose exec -T devkit grep -q 'bootstrap failed' /tmp/devkit.log 2>/dev/null; then why="'bootstrap failed' in devkit.log"
  elif compose exec -T devkit grep -q 'did not start within timeout' /tmp/devkit.log 2>/dev/null; then why="'did not start within timeout' in devkit.log"
  # bash's job report ("... Killed yaci-devkit up") goes to the container log, not devkit.log.
  # Process substitution, not a pipe: pipefail + SIGPIPE must not turn a match into a miss.
  elif grep -qw 'Killed' < <(compose logs --no-color devkit 2>/dev/null); then why="DevKit's process tree was killed — out of memory, or a stale yaci-cli.pid; see e2e/devnet/entrypoint.sh"
  fi
  [[ -z "$why" ]] && return 0
  printf '\n--- tail devkit ---\n' >&2
  { compose exec -T devkit tail -n 40 /tmp/devkit.log || compose logs --no-color --tail 40 devkit; } >&2 2>&1 || true
  fail "devkit failed to boot: $why"
}

facilitators_dead() {
  local svc
  for svc in facilitator facilitator-mempool; do
    if [[ "$(cstate "$svc" '{{.State.Status}}')" == exited ]]; then
      printf '\n--- tail %s ---\n' "$svc" >&2
      compose logs --no-color --tail 40 "$svc" >&2 || true
      fail "$svc container exited"
    fi
  done
  return 0
}

# While the facilitators boot, a dead DevKit can never recover either.
stack_dead() { devkit_dead; facilitators_dead; }

cleanup() {
  local status=$?
  # Logs are kept on every run (CI uploads them); the tails print only on failure.
  mkdir -p "$OUT/logs"
  for svc in devkit shim postgres facilitator facilitator-mempool; do
    compose logs --no-color "$svc" >"$OUT/logs/$svc.log" 2>&1 || true
  done
  # Only when the Java suite ran in this invocation: never stale or merged XML.
  if ((JAVA_RAN)) && [[ -d "$ROOT/build/test-results/e2eTest" ]]; then
    rm -rf "$OUT/junit/java"
    mkdir -p "$OUT/junit/java" && cp -R "$ROOT/build/test-results/e2eTest/." "$OUT/junit/java/" || true
  fi
  compose exec -T devkit cat /tmp/devkit.log >"$OUT/logs/devkit-cli.log" 2>/dev/null || true
  if ((status != 0)); then
    for svc in facilitator facilitator-mempool shim devkit-cli; do
      printf '\n--- tail %s ---\n' "$svc" >&2
      tail -n 40 "$OUT/logs/$svc.log" >&2 || true
    done
    printf '\nFull logs: %s\n' "$OUT/logs" >&2
  fi
  if ((KEEP)); then
    log "stack left running; stop with: docker compose -p $E2E_COMPOSE_PROJECT -f $E2E_COMPOSE_FILE down -v"
  else
    compose down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  exit "$status"
}

mkdir -p "$OUT"
# A run leaves no earlier JUnit XML, load summary or shape dumps behind to pass as its own.
rm -rf "$OUT/junit/java" "$OUT/load" "$OUT/shapes"
trap cleanup EXIT

BOOT=1
if ((REUSE)); then
  KEEP=1
  if ready "$FACILITATOR_URL" && ready "$FACILITATOR_MEMPOOL_URL"; then
    BOOT=0
    log "reusing the running stack"
  else
    log "no ready stack to reuse — booting (and keeping) one"
    if [[ -n "$(compose ps -aq 2>/dev/null)" ]]; then
      echo "containers of project $E2E_COMPOSE_PROJECT exist but are not ready; preflight fails on busy ports. Clear them with:" >&2
      echo "  docker compose -p $E2E_COMPOSE_PROJECT -f $E2E_COMPOSE_FILE down -v" >&2
    fi
  fi
fi

if ((BOOT)); then
  log "preflight"
  for p in "$E2E_YANO_HTTP_PORT" "$E2E_ADMIN_PORT" "$E2E_SHIM_PORT" "$E2E_FACILITATOR_PORT" "$E2E_FACILITATOR_MEMPOOL_PORT"; do
    port_free "$p" || fail "port $p is in use — free it or override the matching E2E_*_PORT"
  done

  if [[ "${E2E_SKIP_BUILD:-0}" != 1 ]]; then
    # Always rebuilt so the run tests the current sources; the layer cache keeps it cheap.
    log "building facilitator image $E2E_IMAGE"
    docker build --platform linux/amd64 -t "$E2E_IMAGE" "$ROOT"
    devkit_args=()
    if [[ "${E2E_CANARY:-0}" == 1 ]]; then
      # A concrete version: the string "latest" would hit the cached npm-install layer forever.
      devkit_version="$(npm view @bloxbean/yaci-devkit version)"
      [[ -n "$devkit_version" ]] || fail "could not resolve the newest @bloxbean/yaci-devkit version"
      echo "canary: building DevKit $devkit_version"
      devkit_args=(--build-arg "YACI_DEVKIT_VERSION=$devkit_version")
    fi
    log "building devkit image"
    compose build ${devkit_args[@]+"${devkit_args[@]}"} devkit
  fi

  log "starting devkit (yano-only, magic 2)"
  compose up -d --no-build devkit
  WATCH=devkit_dead
  wait_for 600 "devkit healthy (genesis magic 2 + fresh tip)" devkit_healthy
  WATCH=

  log "rendering NETWORKS_FILE"
  python3 -I "$E2E/devnet/render-networks.py" "$E2E_ADMIN_URL" "$E2E_MASUMI_DEPLOYMENT" "$OUT/networks.yml"

  log "starting shim, postgres, facilitators"
  compose up -d shim postgres facilitator facilitator-mempool
  WATCH=stack_dead
  wait_for 300 "facilitator readiness UP" ready "$FACILITATOR_URL"
  wait_for 300 "facilitator-mempool readiness UP" ready "$FACILITATOR_MEMPOOL_URL"
  WATCH=

  log "smoke checks"
  curl -fsS "$E2E_SHIM_URL/api/v1/genesis" | grep -Eq '"system_start": *[0-9]+' \
    || fail "shim did not rewrite genesis system_start to an integer"
  for url in "$FACILITATOR_URL" "$FACILITATOR_MEMPOOL_URL"; do
    curl -fsS "$url/supported" | grep -q '"network":"cardano:preview"' || fail "$url/supported lacks cardano:preview"
    curl -fsS "$url/health" | grep -q '"status":"ok"' || fail "$url/health not ok"
  done
fi
echo "stack is up: facilitator=$FACILITATOR_URL mempool=$FACILITATOR_MEMPOOL_URL chain=$E2E_CHAIN_URL"
if [[ "${E2E_CANARY:-0}" == 1 ]]; then
  echo "canary: DevKit running: $(compose exec -T devkit node -p "require('/usr/local/lib/node_modules/@bloxbean/yaci-devkit/package.json').version" 2>/dev/null || echo unknown)"
  for pkg in @x402/cardano @x402/core @evolution-sdk/evolution; do
    echo "canary: newest $pkg $(npm view "$pkg" version 2>/dev/null || echo unknown)"
  done
fi

run_java() {
  log "Java e2e suite"
  JAVA_RAN=1
  rm -rf "$ROOT/build/test-results/e2eTest"   # a compile failure must not copy the previous run's results
  "$ROOT/gradlew" -p "$ROOT" e2eTest ${GRADLE_ARGS[@]+"${GRADLE_ARGS[@]}"}
}

run_ts() {
  log "TypeScript SDK e2e suite"
  mkdir -p "$OUT/junit"
  npm ci --prefix "$E2E/ts" --ignore-scripts || return
  if [[ "${E2E_CANARY:-0}" == 1 ]]; then
    npm install --prefix "$E2E/ts" --no-save --ignore-scripts \
      @x402/cardano@latest @x402/core@latest @evolution-sdk/evolution@latest || return
    local pkg
    for pkg in @x402/cardano @x402/core @evolution-sdk/evolution; do
      echo "canary: $pkg $(node -p "require('$E2E/ts/node_modules/$pkg/package.json').version")"
    done
  fi
  npm test --prefix "$E2E/ts"
}

if ((UP_ONLY)); then
  log "e2e --up: stack is running"
  exit 0
fi

if ((${#GRADLE_ARGS[@]})); then
  run_java || fail "Java suite failed"
else
  # Both suites always run, so one red never hides the other's results.
  java_status=0 ts_status=0
  run_java || java_status=$?
  run_ts || ts_status=$?
  ((java_status == 0 && ts_status == 0)) || fail "Java suite exit $java_status, TypeScript suite exit $ts_status"
fi
log "e2e: PASSED"
