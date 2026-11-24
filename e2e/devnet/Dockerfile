# Yaci DevKit devnet for the e2e suite: the official DevKit image, pinned, plus
# the startup settings and the healthcheck it does not ship. Multi-arch (amd64
# and arm64), and Yano and its runtime are already inside.
ARG YACI_DEVKIT_VERSION=0.12.0
FROM bloxbean/yaci-cli:${YACI_DEVKIT_VERSION}

# PROTOCOLMAGIC=2: the facilitator maps cardano:preview to protocol magic 2.
# NODEMODE=yano-only: in the default companion mode the Haskell node rejects a
# Byron genesis re-signed for magic 2 (InvalidCertificate).
ENV PROTOCOLMAGIC=2 \
    NODEMODE=yano-only

COPY healthcheck.sh /devkit/
RUN chmod +x /devkit/healthcheck.sh

# 6060: Yano's Blockfrost-compatible API. 10000: DevKit admin API (topup, devnet info).
EXPOSE 6060 10000
# --timeout covers healthcheck.sh's two 4 s requests.
HEALTHCHECK --interval=5s --timeout=10s --start-period=60s --retries=60 \
    CMD ["/devkit/healthcheck.sh"]

# The image's own entrypoint runs this bash script with `sh` (dash), which rejects
# its `==` tests, so nothing starts. `up` creates and starts the devnet and stays
# in the foreground for the container's lifetime.
ENTRYPOINT ["/app/yaci-cli.sh", "up"]
