#!/usr/bin/env bash
# One-command devnet e2e run — locally and in CI (.github/workflows/e2e.yml).
#
#   e2e/run.sh                 stack + Java suite + TS suite
#   e2e/run.sh --smoke         stack only, plus route/shim sanity checks
#   e2e/run.sh --java [args]   stack + Java suite (extra args go to Gradle, e.g. --tests '*RoutesE2ETest')
#   e2e/run.sh --ts            stack + TS suite
#   --keep                     leave the stack running afterwards (any mode)
#
# Env: E2E_IMAGE (facilitator image; built from ./Dockerfile when absent),
# E2E_COMPOSE_PROJECT, E2E_{YANO_HTTP,ADMIN,SHIM,FACILITATOR,FACILITATOR_MEMPOOL}_PORT.
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
export E2E_ADMIN_URL="http://127.0.0.1:$E2E_ADMIN_PORT"
export E2E_COMPOSE_FILE="$E2E/compose.e2e.yml"
export E2E_MASUMI_DEPLOYMENT="$E2E/devnet/masumi-deployment.json"

MODE=all KEEP=0 GRADLE_ARGS=()
while (($#)); do
  case "$1" in
    --smoke) MODE=smoke ;;
    --java) MODE=java ;;
    --ts) MODE=ts ;;
    --keep) KEEP=1 ;;
    *) GRADLE_ARGS+=("$1") ;;
  esac
  shift
done

compose() { docker compose -p "$E2E_COMPOSE_PROJECT" -f "$E2E_COMPOSE_FILE" "$@"; }
log() { printf '\n=== %s\n' "$*"; }
fail() { printf '\nFAIL: %s\n' "$*" >&2; exit 1; }

# Poll until a command succeeds or the deadline (seconds) passes.
wait_for() {
  local deadline=$(($(date +%s) + $1)) what=$2; shift 2
  until "$@" >/dev/null 2>&1; do
    (($(date +%s) < deadline)) || fail "timed out after waiting for $what"
    sleep 2
  done
}

port_free() { ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
devkit_healthy() { [[ "$(docker inspect -f '{{.State.Health.Status}}' "$(compose ps -q devkit)")" == healthy ]]; }
ready() { curl -fsS "$1/actuator/health/readiness" | grep -q '"UP"'; }

cleanup() {
  local status=$?
  if ((status != 0)); then
    mkdir -p "$OUT/logs"
    for svc in devkit shim postgres facilitator facilitator-mempool; do
      compose logs --no-color "$svc" >"$OUT/logs/$svc.log" 2>&1 || true
    done
    compose exec -T devkit cat /tmp/devkit.log >"$OUT/logs/devkit-cli.log" 2>/dev/null || true
    for svc in facilitator facilitator-mempool shim devkit-cli; do
      printf '\n--- tail %s ---\n' "$svc" >&2
      tail -n 40 "$OUT/logs/$svc.log" >&2 || true
    done
    printf '\nFull logs: %s\n' "$OUT/logs" >&2
  fi
  if ((KEEP)); then
    log "stack left running (--keep); stop with: docker compose -p $E2E_COMPOSE_PROJECT -f $E2E_COMPOSE_FILE down -v"
  else
    compose down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  exit "$status"
}

log "preflight"
for p in "$E2E_YANO_HTTP_PORT" "$E2E_ADMIN_PORT" "$E2E_SHIM_PORT" "$E2E_FACILITATOR_PORT" "$E2E_FACILITATOR_MEMPOOL_PORT"; do
  port_free "$p" || fail "port $p is in use — free it or override the matching E2E_*_PORT"
done
mkdir -p "$OUT"
trap cleanup EXIT

if ! docker image inspect "$E2E_IMAGE" >/dev/null 2>&1; then
  log "building facilitator image $E2E_IMAGE"
  docker build --platform linux/amd64 -t "$E2E_IMAGE" "$ROOT"
fi

log "starting devkit (yano-only, magic 2)"
# CI loads a cached image first; locally it is built once and reused.
docker image inspect x402-e2e-devkit:local >/dev/null 2>&1 || compose build devkit
compose up -d --no-build devkit
wait_for 600 "devkit healthy (genesis magic 2 + fresh tip)" devkit_healthy

log "rendering NETWORKS_FILE"
python3 -I "$E2E/devnet/render-networks.py" "$E2E_ADMIN_URL" "$E2E_MASUMI_DEPLOYMENT" "$OUT/networks.yml"

log "starting shim, postgres, facilitators"
compose up -d shim postgres facilitator facilitator-mempool
wait_for 300 "facilitator readiness UP" ready "$FACILITATOR_URL"
wait_for 300 "facilitator-mempool readiness UP" ready "$FACILITATOR_MEMPOOL_URL"

log "smoke checks"
curl -fsS "$E2E_SHIM_URL/api/v1/genesis" | grep -Eq '"system_start": *[0-9]+' \
  || fail "shim did not rewrite genesis system_start to an integer"
for url in "$FACILITATOR_URL" "$FACILITATOR_MEMPOOL_URL"; do
  curl -fsS "$url/supported" | grep -q '"network":"cardano:preview"' || fail "$url/supported lacks cardano:preview"
  curl -fsS "$url/health" | grep -q '"status":"ok"' || fail "$url/health not ok"
done
echo "stack is up: facilitator=$FACILITATOR_URL mempool=$FACILITATOR_MEMPOOL_URL chain=$E2E_CHAIN_URL"

run_java() {
  log "Java e2e suite"
  # The outage tests pause the shim, so they run last, in their own invocation.
  "$ROOT/gradlew" -p "$ROOT" e2eTest -PexcludeTags=outage ${GRADLE_ARGS[@]+"${GRADLE_ARGS[@]}"}
  "$ROOT/gradlew" -p "$ROOT" e2eTest -PincludeTags=outage ${GRADLE_ARGS[@]+"${GRADLE_ARGS[@]}"}
}

run_ts() {
  log "TypeScript SDK e2e suite"
  npm ci --prefix "$E2E/ts" --ignore-scripts
  npm test --prefix "$E2E/ts"
}

case "$MODE" in
  smoke) ;;
  java) run_java ;;
  ts) run_ts ;;
  all) run_ts; run_java ;;
esac
log "e2e $MODE: PASSED"
