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
# The image build's prefetch leaves yaci-cli.pid behind (PID 30). On start, `up` SIGKILLs the
# process tree of whatever PID that file names, and in a fresh container PID 30 can be one of
# DevKit's own startup processes, so DevKit dies a few seconds in ("9 Killed yaci-devkit up").
rm -f "$HOME/.yaci-cli/yaci-cli.pid"
yaci-devkit up 2>&1 | sed -u 's/\x1b\[[0-9;]*m//g' | tee /tmp/devkit.log &

# `up` may return once the devnet runs or keep running in the foreground; either
# way the container lives as long as the devnet does.
wait -n || true
exec tail -f /dev/null
