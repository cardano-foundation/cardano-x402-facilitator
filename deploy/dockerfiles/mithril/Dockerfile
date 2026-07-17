# Mithril client image — restores a trusted, signed Cardano node DB snapshot so
# the local cardano-node starts near tip instead of syncing from genesis.
# Adapted from cardano-foundation/cardano-rosetta-java
# (docker/dockerfiles/mithril). Two stages: build the mithril-client CLI from
# source, then a slim runner that runs the download entrypoint.
FROM ubuntu:24.04 AS mithril-builder

SHELL ["/bin/bash", "-c"]
WORKDIR /root/src

# Harden apt against transient Ubuntu-mirror hiccups.
RUN printf 'Acquire::Retries "5";\nAcquire::http::Timeout "30";\nAcquire::https::Timeout "30";\nAcquire::http::No-Cache "true";\n' \
        > /etc/apt/apt.conf.d/99-retries
RUN apt update --fix-missing \
    && apt install -y --no-install-recommends curl ca-certificates \
    && apt-get clean

ARG MITHRIL_VERSION=2617.0
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | bash -s -- -y \
    && export PATH="$HOME/.cargo/bin:$PATH" \
    && apt update --fix-missing \
    && apt install -y --no-install-recommends build-essential m4 libssl-dev jq git pkg-config \
    && apt-get clean
RUN git clone https://github.com/input-output-hk/mithril.git \
    && export PATH="$HOME/.cargo/bin:$PATH" \
    && cd mithril \
    && git checkout $MITHRIL_VERSION \
    && cd mithril-client-cli \
    && make build \
    && mkdir -p /root/.local/bin \
    && cp mithril-client /root/.local/bin/

FROM ubuntu:24.04 AS mithril-runner
COPY --from=mithril-builder /root/.local/bin/mithril-client /usr/local/bin/
RUN printf 'Acquire::Retries "5";\nAcquire::http::Timeout "30";\nAcquire::https::Timeout "30";\nAcquire::http::No-Cache "true";\n' \
        > /etc/apt/apt.conf.d/99-retries \
    && apt update --fix-missing \
    && apt install -y --no-install-recommends wget ca-certificates \
    && apt-get clean
COPY deploy/dockerfiles/mithril/entrypoint.sh /sbin/entrypoint.sh
RUN chmod +x /sbin/entrypoint.sh
CMD ["/sbin/entrypoint.sh"]
