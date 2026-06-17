---
title: Getting started
description: Build HiveKeeper and run the whole stack locally with one script.
---

## Build

Requires a **JDK 21** (the project compiles/tests on Java 21). The Gradle wrapper is committed.

```sh
./gradlew build           # compile + test all modules
./gradlew :hive-cli:run --args="inventory 192.168.x.x"
```

The Gradle daemon may run on a newer JDK; the project pins a **Java 21 toolchain**. Local JDK locations
are configured in `gradle.properties` (`org.gradle.java.installations.paths`) — adjust those to your
machine, or add the [foojay-resolver](https://github.com/gradle/foojay-toolchains) plugin to
auto-provision a JDK 21.

## Run the whole stack

The web UI (`hive-web`) needs **Node + pnpm**. Two scripts bring up everything and open the browser at
**http://localhost:5173** (use `localhost`, not `127.0.0.1` — Vite binds IPv6); press Enter in the window
to stop:

```powershell
# Solo: single user, single AP, no sign-in, no DB. The simplest way to manage one AP locally.
powershell -ExecutionPolicy Bypass -File scripts/run-solo.ps1

# Full local dev: hive-server (:8080) + hive-gateway (:8090, demo tenants) + an agent + the UI.
powershell -ExecutionPolicy Bypass -File scripts/run-local.ps1
```

Both default the AP's SSH login to the public Aerohive defaults (`admin` / `aerohive`); override with
`HIVEKEEPER_DEFAULT_USER` / `HIVEKEEPER_DEFAULT_PASSWORD` before running.

To run pieces by hand, each JVM service is an `application`-plugin module (`./gradlew :hive-gateway:run`,
`:hive-server:run`, etc.) and the UI is `cd hive-web && pnpm install && pnpm dev`.

## First steps in the UI

- **Solo** (`run-solo.ps1`): no sign-in. Go to **Agents → Discover → Adopt**, then manage the AP under **Devices**.
- **Direct mode** (`run-local.ps1`): enter `192.168.1.101` → Discover / Inventory / Backup.
- **Gateway mode** (`run-local.ps1`): tenant key `acme-key` → pick `lab-agent` → inventory the AP through it.
  (`globex-key` is a second tenant that sees no agents — tenant isolation.)

See [Authentication](/authentication/) for what each sign-in mode means and how to run without Keycloak.

## CLI

The `hive-cli` front-end talks to the engine directly (no server needed):

| Command | What it does |
| --- | --- |
| `inventory -h HOST` | Model, firmware, serial, radios, connected stations. |
| `backup -h HOST` | Git-version the running-config (`--no-users`, `--no-secrets` to trim). |
| `capture -h HOST -c "show ..."` | Run arbitrary CLI commands and dump verbatim output. |
| `discover [CIDR]` | Sweep a subnet for SSH-reachable hosts. |
| `configure-ssid -h HOST -n NAME` | Create/remove a WPA2-PSK SSID (`--psk`, `--vlan`, `--remove`). |
| `configure-hive -h HOST -n HIVE -w PWD` | Join a Hive/mesh. |
| `reboot -h HOST --yes` | Reboot the device. |
| `restore -h HOST -f PATH` | Replay a saved config. |

Common SSH options: `-u USER`, `-p PASSWORD`, `-P PORT`.
