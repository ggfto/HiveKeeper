# HiveKeeper web console. Build from the repo ROOT (the build needs docs/ for the in-app help):
#   docker build -f hive-web/Dockerfile -t hivekeeper-web .
#
# Static bundle behind nginx, which also proxies the console's /gw calls to the gateway — in development that
# proxy is the Vite dev server's job, and it does not exist in production.
#
# NOTHING deployment-specific is baked in. The identity provider is not compiled into the bundle: the console
# asks the gateway for it at runtime (GET /api/mode), so this one image works for every self-hoster. The only
# build-time input is the code itself.

# --- build stage ---------------------------------------------------------------
FROM node:22-alpine AS build
RUN corepack enable
WORKDIR /src

# Dependencies first, so a source-only change does not re-resolve the whole tree. corepack reads the exact pnpm
# version from package.json's "packageManager" field: unpinned, it fetches whatever is newest, and a pnpm whose
# handling of build scripts has moved on refuses to run esbuild's — which Vite cannot build without.
COPY hive-web/package.json hive-web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

# The console renders the project docs as in-app Help; `pnpm build` runs scripts/sync-docs.mjs, which reads
# them from the repo root. Hence the root build context.
COPY hive-web/ ./
COPY docs/ /docs/
RUN pnpm build

# --- runtime stage -------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.31-alpine

# Patch the base image's OS packages — an Alpine base drifts behind the distro's security fixes between its own
# rebuilds, and the CI image scan fails the build on a fixable CRITICAL/HIGH. Briefly root to run apk, then
# back to the unprivileged uid the base image runs as.
USER root
RUN apk upgrade --no-cache
USER 101

# nginx-unprivileged runs as uid 101 and cannot bind :80 — it listens on :8080, which is what the config says.
# Running the console as root to serve static files would be a gratuitous risk.
COPY --from=build /src/dist/ /usr/share/nginx/html/

# The gateway's address is the one thing this image cannot know. nginx has no env substitution of its own, so
# the base image's /docker-entrypoint.d/20-envsubst-on-templates.sh renders any *.template in here at start,
# substituting only the variables we list.
COPY hive-web/docker/nginx.conf /etc/nginx/templates/default.conf.template
ENV HIVEKEEPER_GATEWAY_ORIGIN=http://gateway:8090 \
    NGINX_ENVSUBST_FILTER=HIVEKEEPER_

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/index.html >/dev/null 2>&1 || exit 1
