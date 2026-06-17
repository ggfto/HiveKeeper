# hive-web

The HiveKeeper web UI — a small Vite + React app. It has two modes:

- **Direct** — talks to `hive-server` (`/api`): discover the LAN, then inventory/backup an AP directly.
- **Gateway** — talks to `hive-gateway` (`/gw`): enter a tenant key, pick a connected agent, and
  inventory/backup an AP **through** it (cloud → agent → AP; credentials stay on the agent).

It is intentionally NOT a Gradle module; it's a standalone npm/pnpm project.

## Run the whole stack (one command)

From the repo root:

```powershell
# Full local dev: hive-server + hive-gateway (demo tenants) + an agent + the UI.
powershell -ExecutionPolicy Bypass -File scripts/run-local.ps1

# Solo: single user, single AP, no sign-in, no DB — the simplest way to manage one AP locally.
powershell -ExecutionPolicy Bypass -File scripts/run-solo.ps1
```

Either builds + starts the backends, an enrolled `hive-agent`, and the Vite UI, then opens
**http://localhost:5173** (use `localhost`, not `127.0.0.1` — Vite binds IPv6). Press Enter in that window
to stop everything. See the root README's [Authentication](../README.md#authentication-the-gateway-runs-fine-without-keycloak)
section for the sign-in modes (solo / tenant-key / OIDC).

- **Solo** (`run-solo.ps1`): no sign-in. Go to **Agents → Discover → Adopt**, then manage the AP under **Devices**.
- **Direct mode** (`run-local.ps1`): enter `192.168.1.101` → Discover / Inventory / Backup.
- **Gateway mode** (`run-local.ps1`): tenant key `acme-key` → pick `lab-agent` → inventory the AP through it.
  (`globex-key` is a second tenant that sees no agents — tenant isolation.)

## What you can do in the UI

Pages (gateway mode):

- **Overview** — fleet counts (agents / devices / sites / groups) and recent operations.
- **Map** — visual sites → APs → clients topology with live online/offline status.
- **Agents** — list connected agents, discover hosts on their LAN, and adopt new devices into the fleet.
- **Devices** — the managed-fleet table; open one to manage it.
- **Device detail** — a per-AP page with config sections: **Overview** (label/site/groups + inventory/backup),
  **Wi-Fi** (WPA2-PSK SSIDs + VLAN), **Captive portal**, **Mesh**, **Radio** (band/channel/width/Tx power),
  **Client mode**, **Network** (IP/routing/DHCP/DNS), **Monitoring** (SNMP/syslog + live clients/radios/log),
  **Advanced** (raw HiveOS CLI), and **Power** (reboot + LED).
- **Sites & groups** — organize the fleet.
- **Bulk** — run inventory/backup across an org / site / group scope.
- **Members** — manage org members and roles (OIDC mode only).
- **Audit** — the org operation log.

In **solo** mode there are no orgs or sign-in — the same device management is reachable directly.
Most config sections work by generating HiveOS CLI and applying it through the agent (the **Advanced**
section exposes that path raw). See the root README's
[What it can do today](../README.md#what-it-can-do-today) for the full capability list.

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
