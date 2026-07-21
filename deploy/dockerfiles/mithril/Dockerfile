# Mithril client image — restores a trusted, signed Cardano node DB snapshot so
# the local cardano-node starts near tip instead of syncing from genesis.
#
# Uses the PREBUILT mithril-client release binary rather than compiling from
# source: the source build pulled in the full Rust toolchain + the mithril
# monorepo and left multi-GB build cache behind, which is a large part of why a
# fresh `docker compose up` runs the Docker disk out of space. The published
# binary is ~one file and the whole image is ~ubuntu-base + that binary.
FROM ubuntu:24.04

# Distribution tag (https://github.com/input-output-hk/mithril/releases).
ARG MITHRIL_VERSION=2617.0
# Docker sets TARGETARCH to amd64|arm64; Mithril names its tarballs x64|arm64.
ARG TARGETARCH

RUN printf 'Acquire::Retries "5";\nAcquire::http::Timeout "30";\nAcquire::https::Timeout "30";\nAcquire::http::No-Cache "true";\n' \
        > /etc/apt/apt.conf.d/99-retries \
    && apt-get update --fix-missing \
    && apt-get install -y --no-install-recommends wget ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN set -eu; \
    case "${TARGETARCH:-amd64}" in \
        amd64) march=x64 ;; \
        arm64) march=arm64 ;; \
        *) echo "unsupported TARGETARCH '${TARGETARCH}'"; exit 1 ;; \
    esac; \
    url="https://github.com/input-output-hk/mithril/releases/download/${MITHRIL_VERSION}/mithril-${MITHRIL_VERSION}-linux-${march}.tar.gz"; \
    echo "Fetching prebuilt mithril-client: $url"; \
    wget -q --tries=5 --timeout=30 -O /tmp/mithril.tar.gz "$url"; \
    tar -xzf /tmp/mithril.tar.gz -C /usr/local/bin mithril-client; \
    rm /tmp/mithril.tar.gz; \
    chmod +x /usr/local/bin/mithril-client; \
    mithril-client --version

COPY deploy/dockerfiles/mithril/entrypoint.sh /sbin/entrypoint.sh
RUN chmod +x /sbin/entrypoint.sh
CMD ["/sbin/entrypoint.sh"]
