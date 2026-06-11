# hive-web

The HiveKeeper web UI — a small Vite + React app that talks to `hive-server` over `/api`.
It is intentionally NOT a Gradle module; it's a standalone npm/pnpm project.

## Dev

Two terminals:

```sh
# 1) the local server (REST + SSE on 127.0.0.1:8080)
./gradlew :hive-server:run

# 2) the web UI (Vite dev server on http://localhost:5173, proxies /api -> 8080)
cd hive-web
pnpm install
pnpm dev
```

Open http://localhost:5173, enter an AP's host/credentials, and use **Inventory**
(live progress via SSE), **Backup**, or **Discover LAN**.

## Build

```sh
pnpm build      # outputs static assets to dist/
```

For production, `dist/` can be served by any static host, or copied into the server image.
