# Yaci DevKit devnet for the e2e suite — the npm distribution, pinned.
#
# linux/amd64: the npm package ships a linux-x64 binary (and macos-arm64), and
# GitHub's ubuntu-latest runners are x64. Apple Silicon runs it emulated.
FROM --platform=linux/amd64 node:22-bookworm

# The node image sets NODE_VERSION (the Node.js version, e.g. 22.x). DevKit is a
# Spring Boot app, and relaxed binding reads NODE_VERSION as its `node.version`
# property — the cardano-node release to download — which then 404s. An empty
# value would still bind, so every DevKit invocation (here and in entrypoint.sh)
# runs with the variable unset.
ARG YACI_DEVKIT_VERSION=0.12.0
RUN npm install -g @bloxbean/yaci-devkit@${YACI_DEVKIT_VERSION}

# Bake the runtime components into the image so a CI run does not depend on
# GitHub release downloads. DevKit fetches cardano-node even in yano-only mode.
COPY prefetch.sh /devkit/
RUN bash /devkit/prefetch.sh

COPY entrypoint.sh healthcheck.mjs /devkit/
RUN chmod +x /devkit/entrypoint.sh

# 6060: Yano's Blockfrost-compatible API. 10000: DevKit admin API (topup, devnet info).
EXPOSE 6060 10000
HEALTHCHECK --interval=5s --timeout=5s --start-period=60s --retries=60 \
    CMD node /devkit/healthcheck.mjs
ENTRYPOINT ["/devkit/entrypoint.sh"]
