# hive-web

The HiveKeeper web UI — a small Vite + React app. It has two modes:

- **Direct** — talks to `hive-server` (`/api`): discover the LAN, then inventory/backup an AP directly.
- **Gateway** — talks to `hive-gateway` (`/gw`): enter a tenant key, pick a connected agent, and
  inventory/backup an AP **through** it (cloud → agent → AP; credentials stay on the agent).

It is intentionally NOT a Gradle module; it's a standalone npm/pnpm project.

## Run the whole stack (one command)

From the repo root:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-local.ps1
```

This builds + starts `hive-server` (:8080), `hive-gateway` (:8090), an enrolled `hive-agent`, and the
Vite UI, then opens **http://localhost:5173** (use `localhost`, not `127.0.0.1` — Vite binds IPv6).
Press Enter in that window to stop everything.

- **Direct mode**: enter `192.168.1.101` → Discover / Inventory / Backup.
- **Gateway mode**: tenant key `acme-key` → pick `lab-agent` → inventory the AP through it.
  (`globex-key` is a second tenant that sees no agents — tenant isolation.)

## Dev (manual)

```sh
./gradlew :hive-server:run          # and/or :hive-gateway:run + the agent
cd hive-web && pnpm install && pnpm dev
```

## Build

```sh
pnpm build      # outputs static assets to dist/
```

For production, `dist/` can be served by any static host, or copied into the server image.
