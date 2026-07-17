#!/bin/bash
# Restore a signed Cardano node DB snapshot via Mithril into /node so the local
# cardano-node bootstraps near tip. Adapted from cardano-rosetta-java. Runs once
# (compose `restart: "no"`); downstream services gate on
# service_completed_successfully. Always exits 0 so a steady-state (already
# synced) restart never blocks the node.
set -uo pipefail

download_mithril_snapshot() {
    echo "Downloading Mithril snapshot for network: $NETWORK"
    export CARDANO_NETWORK=$NETWORK
    case $NETWORK in
    mainnet)
      AGGREGATOR_ENDPOINT=${AGGREGATOR_ENDPOINT:-https://aggregator.release-mainnet.api.mithril.network/aggregator}
      ANCILLARY_VERIFICATION_KEY=${ANCILLARY_VERIFICATION_KEY:-$(wget -q -O - https://raw.githubusercontent.com/input-output-hk/mithril/main/mithril-infra/configuration/release-mainnet/ancillary.vkey)}
      ;;
    preprod)
      AGGREGATOR_ENDPOINT=${AGGREGATOR_ENDPOINT:-https://aggregator.release-preprod.api.mithril.network/aggregator}
      ANCILLARY_VERIFICATION_KEY=${ANCILLARY_VERIFICATION_KEY:-$(wget -q -O - https://raw.githubusercontent.com/input-output-hk/mithril/main/mithril-infra/configuration/release-preprod/ancillary.vkey)}
      ;;
    preview)
      AGGREGATOR_ENDPOINT=${AGGREGATOR_ENDPOINT:-https://aggregator.pre-release-preview.api.mithril.network/aggregator}
      ANCILLARY_VERIFICATION_KEY=${ANCILLARY_VERIFICATION_KEY:-$(wget -q -O - https://raw.githubusercontent.com/input-output-hk/mithril/main/mithril-infra/configuration/pre-release-preview/ancillary.vkey)}
      ;;
    *)
      echo "Unsupported NETWORK: $NETWORK"; exit 0 ;;
    esac
    export AGGREGATOR_ENDPOINT

    # One-time --allow-override to migrate a legacy /node the first time this
    # v2-aware entrypoint restores here; never again, or a restart would clobber
    # an already-synced (possibly ahead) DB. Marker is our own provenance flag.
    MITHRIL_V2_MARKER="/node/.mithril-v2.done"
    OVERRIDE=""
    if [ -n "$(ls -A /node 2>/dev/null)" ] && [ ! -f "$MITHRIL_V2_MARKER" ]; then
        echo "Pre-existing /node without v2 marker -> one-time --allow-override"
        OVERRIDE="--allow-override"
    fi

    mithril-client cardano-db download latest \
        --include-ancillary \
        --ancillary-verification-key "$ANCILLARY_VERIFICATION_KEY" \
        --download-dir /node $OVERRIDE
    MITHRIL_RC=$?
    if [ "$MITHRIL_RC" -eq 0 ]; then
        touch "$MITHRIL_V2_MARKER"
        echo "Mithril snapshot restored (v2 marker set)"
    else
        # In steady state mithril exits non-zero BY DESIGN (refuses to overwrite);
        # keep the existing DB and let the node continue from where it is.
        echo "Mithril download exited $MITHRIL_RC (kept existing /node data)"
    fi
}

echo "NETWORK=$NETWORK MITHRIL_SYNC=${MITHRIL_SYNC:-false}"
if [ "${MITHRIL_SYNC:-false}" == "true" ]; then
    download_mithril_snapshot
fi
exit 0
