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
COPY server ./server
COPY public ./public
RUN pnpm build && pnpm prune --prod

FROM base AS runtime
ARG GH_VERSION=2.100.0
ARG CODEX_VERSION=0.153.4
ARG TARGETARCH
ARG VCS_REF=development
ENV NODE_ENV=production HOST=0.0.0.0 PORT=4310 DATA_DIR=/data AGENT_HOME=/home/node WORKSPACE_ROOTS=/workspaces
ENV APP_COMMIT=$VCS_REF
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl git ripgrep fd-find python3 build-essential \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s /usr/bin/fdfind /usr/local/bin/fd
RUN arch="${TARGETARCH:-amd64}" \
    && curl -fsSL "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_linux_${arch}.tar.gz" -o /tmp/gh.tar.gz \
    && curl -fsSL "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_checksums.txt" -o /tmp/gh-checksums.txt \
    && expected=$(awk -v name="gh_${GH_VERSION}_linux_${arch}.tar.gz" '$2 == name {print $1}' /tmp/gh-checksums.txt) \
    && test -n "$expected" && printf '%s  /tmp/gh.tar.gz\n' "$expected" | sha256sum --check - \
    && tar -xzf /tmp/gh.tar.gz -C /tmp \
    && cp "/tmp/gh_${GH_VERSION}_linux_${arch}/bin/gh" /usr/local/bin/gh \
    && rm -rf /tmp/gh*
RUN pnpm add --global "@openai/codex@${CODEX_VERSION}"
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --from=build --chown=node:node /app/server ./server
COPY --from=build --chown=node:node /app/shared ./shared
COPY --from=build --chown=node:node /app/package.json ./package.json
RUN mkdir -p /data /workspaces /home/node/.agents/skills /home/node/.codex \
    && chown -R node:node /data /workspaces /home/node /app
USER node
VOLUME ["/data", "/home/node", "/workspaces"]
EXPOSE 4310
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s CMD node -e "fetch('http://127.0.0.1:4310/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "--import", "tsx", "server/index.ts"]
