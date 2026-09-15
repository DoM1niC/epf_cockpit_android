# syntax=docker/dockerfile:1.27
#
# EPF Cockpit Web — Node 22 + Express + better-sqlite3 + Tailwind.
# Multi-stage: builder installiert Deps + baut Tailwind, runtime hat nur
# was zur Laufzeit gebraucht wird (kein g++, kein npm-Cache, keine dev-Deps).

# ---------- builder ----------
FROM node:22-bookworm-slim AS builder

WORKDIR /app

# Build-Tools als Fallback für native Deps ohne passendes Prebuild.
# better-sqlite3 bringt seit v13 fertige N-API-Binaries für x64/arm64 direkt
# im Paket mit und kompiliert beim Install nicht mehr selbst. Werden mit dem
# Stage verworfen, im Runtime-Image nicht enthalten.
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 make g++ \
    && rm -rf /var/lib/apt/lists/*

# Erst nur package*.json kopieren — Docker-Layer-Cache hält npm ci stabil
# solange sich die Lockfile nicht ändert.
COPY package.json package-lock.json ./
RUN npm ci

# Restlichen Source dazu + Build (Tailwind CSS + uPlot-Vendor-Copy).
COPY . .
RUN npm run build

# dev-Deps wegwerfen, damit die runtime-stage nur die laufzeit-nötigen
# node_modules per COPY bekommt. Tailwind etc. fliegt hier raus.
RUN npm prune --omit=dev

# ---------- runtime ----------
FROM node:22-bookworm-slim AS runtime

WORKDIR /app

# Built artifacts + production-only node_modules. `node`-User existiert bereits
# im offiziellen Node-Image mit UID 1000.
COPY --from=builder --chown=node:node /app/node_modules ./node_modules
COPY --from=builder --chown=node:node /app/public        ./public
COPY --from=builder --chown=node:node /app/src           ./src
COPY --from=builder --chown=node:node /app/scripts       ./scripts
COPY --from=builder --chown=node:node /app/package.json  ./

# Persist-Verzeichnis für SQLite WAL — wird per Volume gemountet.
RUN mkdir -p /app/data && chown -R node:node /app/data
VOLUME ["/app/data"]

USER node

ENV NODE_ENV=production \
    PORT=8787 \
    BIND_ADDR=0.0.0.0 \
    DB_PATH=/app/data/epf.db

EXPOSE 8787

# Health-Probe gegen den public /healthz endpoint. fetch() ist seit Node 18
# global; Node 22 hier garantiert.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD node -e "fetch('http://127.0.0.1:${PORT:-8787}/healthz').then(r=>r.ok?process.exit(0):process.exit(1)).catch(()=>process.exit(1))"

CMD ["node", "src/server.js"]
