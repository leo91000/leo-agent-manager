# syntax=docker/dockerfile:1
FROM node:24-bookworm-slim AS base
ENV PNPM_HOME=/pnpm
ENV PATH=$PNPM_HOME/bin:$PNPM_HOME:$PATH
RUN npm install --global pnpm@12.3.4
WORKDIR /app

FROM base AS build
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,id=pnpm-store,target=/pnpm/store pnpm install --frozen-lockfile
COPY tsconfig.json vite.config.ts index.html ./
COPY src ./src
COPY shared ./shared
COPY public ./public
RUN pnpm build

FROM rust:1.97.1-bookworm AS backend
WORKDIR /build
COPY Cargo.toml Cargo.lock ./
COPY backend/Cargo.toml ./backend/Cargo.toml
# A separate dependency layer survives application edits in remote BuildKit
# caches. Cargo cache mounts alone do not persist on fresh GitHub runners.
RUN --mount=type=cache,id=cargo-registry,target=/usr/local/cargo/registry \
    mkdir -p backend/src && printf 'fn main() {}\n' > backend/src/main.rs \
    && printf '' > backend/src/lib.rs && cargo build --locked --release --bin leo
COPY backend ./backend
RUN --mount=type=cache,id=cargo-registry,target=/usr/local/cargo/registry \
    touch backend/src/main.rs backend/src/lib.rs && \
    cargo build --locked --release --bin leo && cp target/release/leo /usr/local/bin/leo

FROM base AS runtime
ARG PLAYWRIGHT_VERSION=1.63.0
# OS libraries are shared by project-pinned browser versions in the persistent home.
# Keep this layer independent of application code and package versions.
RUN npm exec --yes --package="playwright@${PLAYWRIGHT_VERSION}" -- playwright install-deps chromium firefox webkit \
    && rm -rf /var/lib/apt/lists/* /root/.npm
ARG GH_VERSION=2.100.0
ARG CODEX_VERSION=0.154.0
ARG TARGETARCH
ENV NODE_ENV=production HOST=0.0.0.0 PORT=4310 DATA_DIR=/data AGENT_HOME=/home/node WORKSPACE_ROOTS=/workspaces
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl wget git git-lfs openssh-client python3 build-essential bubblewrap \
    zip unzip xz-utils zstd bzip2 rsync file less tree sqlite3 postgresql-client \
    dnsutils iproute2 iputils-ping netcat-openbsd procps lsof strace patch diffutils \
    pkg-config libssl-dev libffi-dev ninja-build poppler-utils imagemagick ffmpeg \
    && rm -rf /var/lib/apt/lists/*
RUN arch="${TARGETARCH:-amd64}" \
    && curl -fsSL "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_linux_${arch}.tar.gz" -o /tmp/gh.tar.gz \
    && curl -fsSL "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_checksums.txt" -o /tmp/gh-checksums.txt \
    && expected=$(awk -v name="gh_${GH_VERSION}_linux_${arch}.tar.gz" '$2 == name {print $1}' /tmp/gh-checksums.txt) \
    && test -n "$expected" && printf '%s  /tmp/gh.tar.gz\n' "$expected" | sha256sum --check - \
    && tar -xzf /tmp/gh.tar.gz -C /tmp \
    && cp "/tmp/gh_${GH_VERSION}_linux_${arch}/bin/gh" /usr/local/bin/gh \
    && rm -rf /tmp/gh*
RUN pnpm add --global "@openai/codex@${CODEX_VERSION}"
COPY deploy/toolkit /opt/leo-toolkit
RUN --mount=type=secret,id=github_token,env=GITHUB_TOKEN /usr/local/bin/node /opt/leo-toolkit/manage.mjs install
# Keep the Codex npm launcher on the manager runtime even in older Node projects.
RUN ln -s /usr/local/bin/node /pnpm/bin/node
COPY deploy/toolkit/profile.sh /etc/profile.d/leo-toolkit.sh
ENV LEO_TOOLKIT_DIR=/opt/leo-toolkit
ENV PATH=/usr/local/bin:/home/node/.local/share/mise/shims:/usr/local/share/mise/shims:$PATH
COPY --from=backend /usr/local/bin/leo /usr/local/bin/leo
COPY --from=build --chown=node:node /app/dist ./dist
COPY --from=build --chown=node:node /app/package.json ./package.json
RUN mkdir -p /data /workspaces /home/node/.agents/skills /home/node/.codex \
    && chown -R node:node /data /workspaces /home/node /app
ARG VCS_REF=development
ENV APP_COMMIT=$VCS_REF APP_RUNTIME_ID=$VCS_REF APP_CODEX_VERSION=$CODEX_VERSION APP_GH_VERSION=$GH_VERSION
USER node
VOLUME ["/data", "/home/node", "/workspaces"]
EXPOSE 4310
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --start-interval=1s CMD /usr/local/bin/node -e "fetch('http://127.0.0.1:4310/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["/usr/local/bin/leo", "serve"]

# Guest kernel is built from a pinned upstream LTS source, not demo VM assets.
FROM debian:bookworm-slim AS guest-kernel
RUN apt-get update && apt-get install -y --no-install-recommends build-essential bc bison flex libssl-dev libelf-dev curl ca-certificates xz-utils && rm -rf /var/lib/apt/lists/*
WORKDIR /kernel
RUN curl -fsSL https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-6.12.109.tar.xz -o linux.tar.xz \
    && echo '5484e552a334e15019f4aeba89e5b58f04651cf2f4e24e04de9f152f1c38e3fa  linux.tar.xz' | sha256sum -c - \
    && tar -xf linux.tar.xz --strip-components=1 && rm linux.tar.xz
COPY deploy/microvm/kernel.config /tmp/leo.config
RUN make x86_64_defconfig && scripts/kconfig/merge_config.sh -m .config /tmp/leo.config \
    && scripts/config --disable MODULES --disable DEBUG_INFO --disable DEBUG_INFO_DWARF_TOOLCHAIN_DEFAULT \
    && make olddefconfig && make -j8 vmlinux && strip --strip-debug vmlinux

FROM runtime AS guest
USER root
RUN apt-get update && apt-get install -y --no-install-recommends docker.io sudo iptables util-linux e2fsprogs \
    && rm -rf /var/lib/apt/lists/* \
    && usermod -aG docker node \
    && printf 'node ALL=(ALL) NOPASSWD: ALL\n' > /etc/sudoers.d/leo \
    && chmod 440 /etc/sudoers.d/leo
COPY --chmod=755 deploy/microvm/init /sbin/leo-init
COPY --chmod=755 deploy/microvm/docker /usr/local/bin/docker

FROM debian:bookworm-slim AS guest-disk
RUN apt-get update && apt-get install -y --no-install-recommends e2fsprogs zstd && rm -rf /var/lib/apt/lists/*
COPY --from=guest / /rootfs/
RUN truncate -s 8G /root.ext4 && mkfs.ext4 -q -F -d /rootfs /root.ext4 \
    && zstd -T2 -3 /root.ext4 -o /root.ext4.zst

FROM runtime AS final
USER root
RUN apt-get update && apt-get install -y --no-install-recommends iptables e2fsprogs util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL https://github.com/firecracker-microvm/firecracker/releases/download/v1.17.0/firecracker-v1.17.0-x86_64.tgz -o /tmp/firecracker.tgz \
    && echo '06094a1108ae9e82aa4c23a775aa92758f53f1175d422270d9d6162cb9ade558  /tmp/firecracker.tgz' | sha256sum -c - \
    && tar -xzf /tmp/firecracker.tgz -C /tmp \
    && cp /tmp/release-v1.17.0-x86_64/firecracker-v1.17.0-x86_64 /usr/local/bin/firecracker \
    && cp /tmp/release-v1.17.0-x86_64/jailer-v1.17.0-x86_64 /usr/local/bin/jailer \
    && rm -rf /tmp/firecracker.tgz /tmp/release-v1.17.0-x86_64
COPY --from=guest-kernel /kernel/vmlinux /opt/leo-vm/vmlinux
COPY --from=guest-kernel /kernel/.config /opt/leo-vm/kernel.config
COPY --from=guest-kernel /kernel/COPYING /opt/leo-vm/KERNEL-COPYING
COPY --from=guest-kernel /kernel/LICENSES /opt/leo-vm/kernel-licenses
COPY --from=guest-disk /root.ext4.zst /opt/leo-vm/root.ext4.zst
COPY --chmod=755 deploy/microvm/init deploy/microvm/docker /opt/leo-vm/
USER node
