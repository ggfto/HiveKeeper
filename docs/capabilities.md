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
- **Wi-Fi** — create / edit / remove SSIDs on a chosen **security suite** (Open, WPA2-PSK, **WPA3-SAE**, or
  **802.1X Enterprise** — WPA2/WPA3 — bound to a **RADIUS** server), with optional VLAN. Per-SSID **hardening**
  (hide-SSID, client cap, client isolation, DTIM, schedule, 802.11k/v), a **minimum data rate** that prunes slow
  802.11b basic rates (1/2/5.5/11 Mbps) to reclaim airtime, per-SSID **QoS** (bind a **classifier** / **marker**
  profile and toggle **WMM** for voice/video priority), and **PPSK** (per-user PSK) with the **self-registration**
  model — a HiveAP serves PPSK and hosts the enrolment portal (optionally RADIUS-authenticated) so users register and
  the AP issues keys locally. HiveKeeper configures the model; it does not mint individual keys.
- **Policy** — **user profiles**: the policy a client lands in (default **VLAN** id or VLAN group, optional
  **QoS policy** and **schedule**), keyed by a numeric attribute (0–4095). List the profiles read from the AP,
  create / overwrite one, **bind** it to an SSID's security object as the default profile, and remove it. Per
  profile: a **bandwidth SLA** (performance-sentinel), **L2/L3 firewall** bindings (`ip-policy` / `mac-policy`
  from/to client + default action) and a **QoS marker-map**. Plus the objects those reference — **IP firewall
  policies** (create + a permit/deny/NAT/redirect rule on a service), **MAC firewall policies** (create), and
  **QoS rate-limit policies** (`qos policy` capping a user profile's kbps) — created and listed here.
- **Schedules** — named **schedule objects** (`schedule <name> recurrent | once`) that gate *when* an SSID or
  user profile is active. List the schedules read from the AP, create a **recurrent** one (by weekday and/or
  time of day, with an optional active date window) or a **one-time** one (between a start and end date+time),
  and remove it. Referenced by name from the Wi-Fi (Hardening) and Policy sections.
- **Network** — the mgt0 interface (IP / VLAN / routing / DHCP-client / DNS / NTP), plus **static routes**
  (net / host, list + add + remove) and **LLDP/CDP** neighbor discovery (enable, timers, receive-only, cache size).
- **Captive portal**, **Mesh/Hive** join, **Client mode**, **Monitoring** (SNMP, syslog), **Power & LED**, **Reboot**.
- **Radio** — per-interface channel, Tx power, operational mode, and **client target power**
  (`tx-power-control`); plus the named **radio profile** that interfaces reference: **channel width**
  (20/40/80 MHz), **band-steering**, **client load-balancing**, and a per-profile **max-clients** cap.
  Best-practice advisories (channel overlap, wide channels, high power) flag settings likely to hurt latency
  under client density, right next to the control that fixes them. A profile can be shared across interfaces
  and APs, so a profile change has a wider blast radius than a per-interface tweak. An **Advanced RF tuning**
  disclosure exposes the dense-RF knobs: per-radio `rx-sop` / `ed-threshold` / `dfs-backup-channel`, and on the
  profile `dfs`, `short-guard-interval`, `ampdu` / `amsdu`, `frameburst`, `tx-beamforming`, `high-density`,
  `weak-snr-suppress`, `phymode`, and receive/transmit chain counts. **Note (confirmed live):** HiveOS refuses
  edits to the *default* radio profiles (`radio_ac0` / `radio_ng0`) — profile knobs must target a **custom**
  profile (the first setting auto-creates it; the form warns on a default name), and `phymode` must be set
  before channel width / beamforming will take. A **Bind to radio** selector then applies the custom profile to
  a radio (`interface wifiN radio profile <name>`) — warned, since it briefly disrupts that radio's clients.
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

Alerting / thresholds, config templates, scheduled reboot, PPSK admin-driven key minting, and any non-HiveOS
vendor (the driver SPI is ready for them). Firmware upgrade ships but is **lab/untested** until validated on
real hardware. The project README's Roadmap tracks the current plan.

A few HiveOS features are **not exposed because the AP230 has no running-config grammar for them** (confirmed
live, so they can't be driven through the SSH/apply-config path HiveKeeper uses):

- **IGMP snooping / multicast** — there is no `igmp` command, no `mgt0` sub-node, and no `show igmp`; the AP
  bridges multicast and IGMP snooping is the upstream switch's job.
- **Tunnel-policy objects** — a user profile can *reference* a `tunnel-policy <name>`, but `tunnel-policy` is not
  a top-level object you can create over SSH (L3 VPN tunneling, out of scope for a standalone AP).
- **Individual PPSK keys** and **MAC-policy rules** — see Wi-Fi (PPSK) and Policy; MAC rules go through Advanced
  raw-CLI as the combined rule line is rejected.
