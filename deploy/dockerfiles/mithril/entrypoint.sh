#!/bin/bash
# Restore a signed Cardano node DB snapshot via Mithril into /node so the local
# cardano-node bootstraps near tip instead of syncing from genesis. Adapted from
# cardano-foundation/cardano-rosetta-java.
#
# One-shot (compose `restart: "no"`); cardano-node gates on
# service_completed_successfully. Exit semantics are deliberate:
#   - fresh (empty) volume + download fails -> exit NON-ZERO, so the node never
#     boots on an empty DB and quietly syncs from genesis; the failure is visible.
#   - already-restored volume + mithril refuses to overwrite -> exit 0, keep the
#     existing (possibly-ahead) DB and let the node continue.
set -uo pipefail

NETWORK=${NETWORK:-preprod}
MITHRIL_V2_MARKER="/node/.mithril-v2.done"

# Fetch a Mithril public key / config value from the IOG config repo, with retries.
fetch() { wget -q --tries=5 --timeout=30 -O - "$1"; }

node_dir_empty() { [ -z "$(ls -A /node 2>/dev/null)" ]; }

download_mithril_snapshot() {
    echo "Downloading Mithril snapshot for network: $NETWORK"
    export CARDANO_NETWORK=$NETWORK

    # Map the network to its Mithril infra config folder + default aggregator.
    local cfg
    case $NETWORK in
    mainnet)
        cfg=release-mainnet
        AGGREGATOR_ENDPOINT=${AGGREGATOR_ENDPOINT:-https://aggregator.release-mainnet.api.mithril.network/aggregator} ;;
    preprod)
        cfg=release-preprod
        AGGREGATOR_ENDPOINT=${AGGREGATOR_ENDPOINT:-https://aggregator.release-preprod.api.mithril.network/aggregator} ;;
    preview)
        cfg=pre-release-preview
        AGGREGATOR_ENDPOINT=${AGGREGATOR_ENDPOINT:-https://aggregator.pre-release-preview.api.mithril.network/aggregator} ;;
    *)
        echo "ERROR: unsupported NETWORK '$NETWORK' (expected mainnet|preprod|preview)"; exit 1 ;;
    esac

    # Two DIFFERENT keys are required:
    #   genesis.vkey   -> anchors the Mithril certificate chain (mandatory for any
    #                     download; missing it is the "genesis_verification_key is
    #                     mandatory" error).
    #   ancillary.vkey -> signs the --include-ancillary files (ledger/volatile/tip).
    # Both default to the published IOG keys but can be overridden via the env.
    local base=https://raw.githubusercontent.com/input-output-hk/mithril/main/mithril-infra/configuration/$cfg
    GENESIS_VERIFICATION_KEY=${GENESIS_VERIFICATION_KEY:-$(fetch "$base/genesis.vkey")}
    ANCILLARY_VERIFICATION_KEY=${ANCILLARY_VERIFICATION_KEY:-$(fetch "$base/ancillary.vkey")}
    export AGGREGATOR_ENDPOINT GENESIS_VERIFICATION_KEY

    if [ -z "$GENESIS_VERIFICATION_KEY" ] || [ -z "$ANCILLARY_VERIFICATION_KEY" ]; then
        echo "ERROR: could not obtain Mithril verification keys for $NETWORK."
        echo "       Check outbound access to raw.githubusercontent.com, or set"
        echo "       GENESIS_VERIFICATION_KEY / ANCILLARY_VERIFICATION_KEY explicitly."
        node_dir_empty && exit 1 || return 0   # fatal only when there's no DB to fall back on
    fi

    # Fresh = first-ever bootstrap into an empty volume (decided BEFORE download).
    local fresh=false
    node_dir_empty && fresh=true

    # One-time --allow-override to migrate a legacy (pre-v2-marker) /node; never
    # again, or a restart would clobber an already-synced (possibly ahead) DB.
    local override=""
    if [ "$fresh" = false ] && [ ! -f "$MITHRIL_V2_MARKER" ]; then
        echo "Pre-existing /node without v2 marker -> one-time --allow-override"
        override="--allow-override"
    fi

    mithril-client cardano-db download latest \
        --include-ancillary \
        --ancillary-verification-key "$ANCILLARY_VERIFICATION_KEY" \
        --download-dir /node $override
    rc=$?

    if [ "$rc" -eq 0 ]; then
        touch "$MITHRIL_V2_MARKER"
        echo "Mithril snapshot restored (v2 marker set)"
        return 0
    fi

    if [ "$fresh" = true ]; then
        # Fresh volume + failed download: do NOT let cardano-node start on an empty
        # DB. Fail loudly so `service_completed_successfully` blocks downstream.
        echo "ERROR: Mithril restore failed on a fresh volume (exit $rc). Aborting so"
        echo "       cardano-node does not boot and sync from genesis. Fix the error"
        echo "       above, or set MITHRIL_SYNC=false to sync from genesis on purpose."
        exit "$rc"
    fi

    # Steady state: mithril refuses to overwrite an existing DB — expected on a
    # restart. Keep what we have and let the node continue from where it is.
    echo "Mithril download exited $rc; kept existing /node data (steady-state restart)"
    return 0
}

echo "NETWORK=$NETWORK MITHRIL_SYNC=${MITHRIL_SYNC:-false}"
if [ "${MITHRIL_SYNC:-false}" == "true" ]; then
    download_mithril_snapshot
fi
exit 0
