---
title: Device configuration
description: The per-AP config sections in the web UI and what each one changes.
---

Open a device under **Devices → (an AP)** to reach its management page. The header runs read-only
**Inventory** and **Backup**; the rest is a vertical nav of config sections. Most sections work by
**generating HiveOS CLI and applying it through the agent** (the **Advanced** section exposes that path
raw), so what you change in the UI is exactly what lands in the running-config.

:::note
Writes that change `save config` persist to flash; reads (inventory, "show" commands behind the live
panels) are strictly read-only. The agent holds the device credential — the gateway never sees it.
:::

| Section | What you configure |
| --- | --- |
| **Overview** | HiveKeeper metadata (label, site, groups) and the read-only inventory header. |
| **Credentials** | Set / rotate the SSH credential HiveKeeper uses for this AP. The secret is sealed to the agent's key (never stored in the cloud) and written to the agent's vault, encrypted at rest. An optional, confirm-gated "also change the password on the AP" toggle rotates the device's admin password too (HiveOS policy: 8–32 chars, ≥1 number, ≥1 uppercase). |
| **Wi-Fi** | Create / edit / remove SSIDs on a chosen **security suite** (Open / WPA2-PSK / WPA3-SAE / 802.1X Enterprise). The enterprise suites bind a **RADIUS** server (IP + shared secret, which is masked everywhere and encrypted at rest); preshared-key suites take a passphrase; open takes neither. Reads the live SSID list from `show running-config`; an edit preserves the SSID's existing suite (no silent downgrade), and 802.1X SSIDs are changed by remove + re-add. Per-SSID **hardening** (hide-SSID, max-client, client isolation, DTIM, schedule, 802.11k/v), a **minimum data rate** picker (2.4 / 5 GHz) that drops slow basic rates, and **PPSK** (per-user PSK) with the **self-registration** model — designate the HiveAP that serves PPSK (`ppsk-server <mgt0-ip>`), enable the enrolment portal (`ppsk-web-server` + HTTPS / web-directory / RADIUS `auth-user`), and bind a user-group. The AP issues + stores keys locally on user registration; HiveKeeper configures the model but does not mint individual keys (admin-driven key minting is roadmap Caminho B). |
| **Captive portal** | A walled-garden captive portal (web directory, HTTP port, allowed hosts). |
| **Mesh** | Join / configure a Hive (mesh): name + shared key, interface binding, thresholds. Reads `show hive`. |
| **Radio** | Per-interface settings (wifi0 = 2.4 GHz, wifi1 = 5 GHz): channel, Tx power, operational mode, and **client target power** (`tx-power-control`). Below it, the named **radio profile** the interfaces reference (defaults `radio_ng0` / `radio_ac0`): **channel width** (20/40/80 MHz), **band-steering**, **client load-balancing**, and a per-profile **max-clients** cap. ⚠️ A profile may be shared across interfaces and APs, so changes here have a wider blast radius than a per-interface tweak. Best-practice advisories sit next to the controls. |
| **Client mode** | Run the AP as a wireless client to an upstream SSID. |
| **Network** | IP address / netmask / default gateway, management & native VLAN, DNS. |
| **Monitoring** | SNMP and syslog, plus a live panel: connected clients, per-radio status, recent on-AP log. |
| **Advanced** | A raw HiveOS CLI escape hatch — type commands, optionally `save config`; rejected lines are highlighted. |
| **Power** | Power settings, the **Reboot** button, LED behavior, and a **Maintenance** block (see below). |

The **Power → Maintenance** block holds two device-lifecycle actions (both operator-level, gated on the
agent being online):

- **Restore config** — pick a saved running-config (e.g. a backup `.txt`); its lines are replayed additively
  and persisted with `save config`.
- **Firmware upgrade** — give a URL the AP can reach (TFTP/FTP/HTTP); HiveKeeper runs `save image` and reboots
  to activate it. ⚠️ **Lab / untested in v0.1** — validate against your hardware first; the AP is offline for
  several minutes, so re-run **Inventory** afterwards to confirm the new version.

For the exact engine commands behind these (and the CLI equivalents), see [Capabilities](/capabilities/)
and [Getting started → CLI](/getting-started/#cli).
