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

- **Credentials** — set or rotate the SSH credential HiveKeeper uses for a device, from the UI. The secret is
  sealed to the on-prem agent's public key at the gateway (it never persists in the cloud) and stored in the
  agent's local vault, encrypted at rest. Optionally it also changes the admin password **on the AP itself**
  (`admin root-admin <user> password …`, validated live on an AP230 — HiveOS requires 8–32 chars with a number
  and an uppercase letter); a wrong value can lock you out, so it is confirm-gated.
- **Wi-Fi** — create / edit / remove WPA2-PSK SSIDs, with optional VLAN, and a per-SSID **minimum data rate**
  that prunes slow 802.11b basic rates (1/2/5.5/11 Mbps) to reclaim airtime in dense deployments.
- **Captive portal**, **Mesh/Hive** join, **Client mode**, **Network** (IP / routing / DHCP / DNS),
  **Monitoring** (SNMP, syslog), **Power & LED**, **Reboot**.
- **Radio** — per-interface channel, Tx power, operational mode, and **client target power**
  (`tx-power-control`); plus the named **radio profile** that interfaces reference: **channel width**
  (20/40/80 MHz), **band-steering**, **client load-balancing**, and a per-profile **max-clients** cap.
  Best-practice advisories (channel overlap, wide channels, high power) flag settings likely to hurt latency
  under client density, right next to the control that fixes them. A profile can be shared across interfaces
  and APs, so a profile change has a wider blast radius than a per-interface tweak.
- **Advanced** — a raw HiveOS CLI escape hatch (send arbitrary commands, optionally `save config`).
- **Backup** — capture the running-config to a git-versioned store, with optional secrets and PPSK users.
- **Restore** — re-apply a saved running-config (additive replay, then `save config`). In the web UI it lives
  under **Power → Maintenance**; on the CLI it is `restore`.
- **Firmware upgrade** — pull an image from a URL the AP can reach (TFTP/FTP/HTTP) and reboot to activate it
  (`save image`). ⚠️ **Lab / untested in v0.1** — validate it against your hardware before relying on it.

## Fleet & multi-org

The gateway:

- **Discover** APs on a subnet (SSH banner sweep), then **adopt** them into a managed fleet.
- Organize devices into **sites** and **groups**; run **bulk** inventory/backup across org/site/group scopes.
- **Members & roles** (viewer / operator / admin / owner) and **agent enrollment** — under the OIDC profile.
- Durable, persisted **jobs** under the `postgres` profile.

## Not yet

Alerting / thresholds, config templates, scheduling, and any non-HiveOS vendor (the driver SPI is ready for
them). Firmware upgrade ships but is **lab/untested** until validated on real hardware. The project README's
Roadmap tracks the current plan.
