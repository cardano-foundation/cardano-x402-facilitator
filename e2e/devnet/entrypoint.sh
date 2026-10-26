#!/usr/bin/env bash
# Starts a fresh single-node devnet that the facilitator accepts as cardano:preview.
#
#   PROTOCOLMAGIC=2   the facilitator hard-maps cardano:preview to magic 2 and checks
#                     the provider's genesis against it.
#   NODEMODE=yano-only  in the default companion mode the Haskell node rejects a
#                     Byron genesis re-signed for magic 2 (InvalidCertificate).
set -euo pipefail
unset NODE_VERSION

export PROTOCOLMAGIC=2
export NODEMODE=yano-only

cd /tmp
yaci-devkit up 2>&1 | sed -u 's/\x1b\[[0-9;]*m//g' | tee /tmp/devkit.log &

# `up` may return once the devnet runs or keep running in the foreground; either
# way the container lives as long as the devnet does.
wait -n || true
exec tail -f /dev/null
