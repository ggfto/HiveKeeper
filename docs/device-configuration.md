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
| **Wi-Fi** | Create / edit / remove WPA2-PSK SSIDs, with an optional VLAN. Reads the live SSID list from `show running-config`. |
| **Captive portal** | A walled-garden captive portal (web directory, HTTP port, allowed hosts). |
| **Mesh** | Join / configure a Hive (mesh): name + shared key, interface binding, thresholds. Reads `show hive`. |
| **Radio** | Per-band radio settings (2.4 / 5 / 6 GHz): channel, width, Tx power. |
| **Client mode** | Run the AP as a wireless client to an upstream SSID. |
| **Network** | IP address / netmask / default gateway, management & native VLAN, DNS. |
| **Monitoring** | SNMP and syslog, plus a live panel: connected clients, per-radio status, recent on-AP log. |
| **Advanced** | A raw HiveOS CLI escape hatch — type commands, optionally `save config`; rejected lines are highlighted. |
| **Power** | Power settings, the **Reboot** button, and LED behavior. |

For the exact engine commands behind these (and the CLI equivalents), see [Capabilities](/capabilities/)
and [Getting started → CLI](/getting-started/#cli).
