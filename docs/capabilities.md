---
title: Capabilities
description: Exactly what HiveKeeper can monitor and configure today.
---

Everything below is wired up and exercised by tests against HiveOS (IQ Engine). The CLI covers the core
engine commands; the web UI adds a richer config surface by generating HiveOS CLI and pushing it through the
agent (the same `apply-config` path the **Advanced** escape hatch exposes raw).

## Monitor / read

- **Inventory** — model, serial, firmware, uptime, management IP, hive/mesh name, and the radio list.
- **Connected clients** — per-station MAC, IP, hostname, SSID, RSSI, OS type.
- **Live status** — per-radio channel / width / Tx power (`show acsp`), whether the AP is standalone or still
  phoning home to the cloud (`show capwap client`), and the recent on-AP log (`show log buffered`).
- **Infrastructure map** — a visual sites → APs → clients topology with live online/offline status.
- **Audit log** — an org-scoped record of operations (who did what, when).

## Configure / write

Per device, via the web UI ([device configuration](/device-configuration/) explains each section):

- **Wi-Fi** — create / edit / remove WPA2-PSK SSIDs, with optional VLAN.
- **Captive portal**, **Mesh/Hive** join, **Radio** (band, channel, width, Tx power), **Client mode**,
  **Network** (IP / routing / DHCP / DNS), **Monitoring** (SNMP, syslog), **Power & LED**, **Reboot**.
- **Advanced** — a raw HiveOS CLI escape hatch (send arbitrary commands, optionally `save config`).
- **Backup** — capture the running-config to a git-versioned store, with optional secrets and PPSK users.

## Fleet & multi-org

The gateway:

- **Discover** APs on a subnet (SSH banner sweep), then **adopt** them into a managed fleet.
- Organize devices into **sites** and **groups**; run **bulk** inventory/backup across org/site/group scopes.
- **Members & roles** (viewer / operator / admin / owner) and **agent enrollment** — under the OIDC profile.
- Durable, persisted **jobs** under the `postgres` profile.

## Not yet

Firmware upgrades, restore-from-backup via the API/UI (the CLI `restore` exists), alerting / thresholds,
config templates, scheduling, and any non-HiveOS vendor (the driver SPI is ready for them). The project
README's Roadmap tracks the current plan.
