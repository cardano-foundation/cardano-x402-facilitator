#!/usr/bin/env bash
# Downloads DevKit's runtime components at image build time.
#
# `yaci-devkit download` finishes the download, prints its result (`true`) and
# then never exits — run plainly, it hangs the docker build forever. So it runs
# in the background, and we stop it once the result line appears.
set -euo pipefail
unset NODE_VERSION # see the Dockerfile
cd /tmp

log=/tmp/prefetch.log
yaci-devkit download -c node yano >"$log" 2>&1 &
pid=$!

for _ in $(seq 600); do
  if grep -qx 'true' "$log"; then
    kill "$pid" 2>/dev/null || true
    pkill -f yaci-cli 2>/dev/null || true
    test -d /root/.yaci-cli/components/yano || { echo "yano component missing" >&2; exit 1; }
    echo "prefetch done"
    exit 0
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    break
  fi
  sleep 1
done

kill "$pid" 2>/dev/null || true
sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -v Extracting | tail -40 >&2
echo "prefetch failed: no success result within 600 s" >&2
exit 1
