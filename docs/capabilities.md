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
- **Wi-Fi** — create / edit / remove SSIDs on a chosen **security suite** (Open, **OWE / Enhanced Open**,
  WPA2-PSK, **WPA3-SAE**, or **802.1X Enterprise** — WPA2 / WPA3 / **WPA3 192-bit Suite B** — bound to a
  **RADIUS** server), with optional VLAN. An SSID is bound to **every radio the AP reports**, so a three-radio
  AP410C-1 carries it on all three rather than only the first two. Per-SSID **hardening**
  (hide-SSID, client cap, client isolation, DTIM, schedule, 802.11k/v and **802.11r** fast roaming with a
  mobility domain id), a **minimum data rate** that prunes slow
  802.11b basic rates (1/2/5.5/11 Mbps) to reclaim airtime, per-SSID **QoS** (bind a **classifier** / **marker**
  profile and toggle **WMM** for voice/video priority), and **PPSK** (per-user PSK) with the **self-registration**
  model — a HiveAP serves PPSK and hosts the enrolment portal (optionally RADIUS-authenticated) so users register and
  the AP issues keys locally. A separate **PPSK via RADIUS** block wires the AP's local PPSK server at an external
  RADIUS backend (`aaa ppsk-server radius-server primary <ip>` + `shared-secret` / `auth-port` / `auto-save-interval`)
  and forwards a security object's private-PSK auth to it (`security-object <so> security private-psk radius-auth
  pap|chap|ms-chap-v2`). On top of that wiring, a **PPSK users** tab **mints, rotates, and revokes per-user
  Private PSKs** that HiveKeeper owns on-prem: the gateway generates the key, seals it to the agent, and the
  agent stores it (encrypted at rest) and provisions a co-located FreeRADIUS — the cloud keeps only metadata + a
  reference, and the generated key is shown **once**. This admin-minting path (Caminho B) is built and
  unit-tested but ships **untested** until the live lab pass (see the runbook); the AP→RADIUS wiring and the
  self-registration model are live-confirmed.
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
- **Captive portal**, **Mesh/Hive** join, **Client mode**, **Monitoring** (SNMP, syslog), **Power & LED**, **Reboot**
  (immediate, plus a **scheduled reboot** — recurring daily/weekly `reboot schedule` with the next reboot read
  from `show reboot schedule` and cancel; one-shot date/offset reboots are intentionally not exposed, as they
  prompt an interactive Y/N the agent can't answer).
- **Radio** — per-interface channel, Tx power, operational mode, and **client target power**
  (`tx-power-control`); plus the named **radio profile** that interfaces reference: **channel width**
  (20/40/80/160 MHz, plus the explicit 40-above / 40-below offsets), **band-steering**, **client
  load-balancing**, and a per-profile **max-clients** cap.
  Best-practice advisories (channel overlap, wide channels, high power) flag settings likely to hurt latency
  under client density, right next to the control that fixes them. A **Channel scan** panel (and `hivekeeper
  scan`) reads `show acsp channel-info` / `show acsp neighbor` to show, per radio, what every candidate
  channel would cost, how many neighbouring APs sit on it and how loud the nearest one is — then names the
  cheapest. The costs are the **AP's own**: it runs automatic channel selection and scores the spectrum
  continuously, so HiveKeeper reports that measurement rather than re-deriving one. Read-only; changing a
  channel stays a deliberate act in the radio form, because it reconnects every client on that radio. A profile can be shared across interfaces
  and APs, so a profile change has a wider blast radius than a per-interface tweak. An **Advanced RF tuning**
  disclosure exposes the dense-RF knobs: per-radio `rx-sop` / `ed-threshold` / `dfs-backup-channel`, and on the
  profile `dfs`, `short-guard-interval`, `ampdu` / `amsdu`, `frameburst`, `tx-beamforming`, `high-density`,
  `weak-snr-suppress`, `phymode` (including `11ax-2g` / `11ax-5g`), and receive/transmit chain counts (1-4).
  A separate **Wi-Fi 6 (802.11ax)** disclosure exposes `11ax bss-color`, `11ax ofdma-dl` / `ofdma-ul`,
  `11ax twt` and `mu-mimo` — every one of which HiveOS ships **disabled**, so a Wi-Fi 6 AP runs without them
  until you turn them on. They need an 11ax phymode, and older hardware rejects the line. **Note (confirmed live):** HiveOS refuses
  edits to the *default* radio profiles (`radio_ac0` / `radio_ng0`) — profile knobs must target a **custom**
  profile (the first setting auto-creates it; the form warns on a default name), and `phymode` must be set
  before channel width / beamforming will take. A **Bind to radio** selector then applies the custom profile to
  a radio (`interface wifiN radio profile <name>`) — warned, since it briefly disrupts that radio's clients.
- **Advanced** — a raw HiveOS CLI escape hatch (send arbitrary commands, optionally `save config`).
- **Backup** — capture the running-config to a git-versioned store, with optional secrets and PPSK users.
- **Restore** — re-apply a saved running-config (additive replay, then `save config`). In the web UI it lives
  under **Power → Maintenance**; on the CLI it is `restore`.
- **Firmware upgrade** — pull an image from a URL the AP can reach (TFTP/FTP/HTTP) and reboot to activate it
  (`save image`). ⚠️ **Lab / untested** — validate it against your hardware before relying on it.

## Fleet & multi-org

The gateway:

- **Discover** APs on a subnet (SSH banner sweep), then **adopt** them into a managed fleet. Adoption captures
  the AP's **current running-config as the "as-adopted" baseline** (a git commit), so an AP a previous admin
  configured has a snapshot to diff and roll back to *before* anyone changes anything through HiveKeeper.
  Adopting the same AP again (from either agent) updates the one device row — an AP is never registered twice.
- **Manage from the AP's current state.** The Wi-Fi, Policy, Network-routes, Schedule and Mesh sections read
  the live running-config and list the AP's **real** objects (SSIDs with their VLAN/security, user profiles,
  routes, schedules, hives) to edit in place — not a blank slate. The **radio-profile** form can load an
  existing profile and pre-fill its current values (channel width, PHY mode, chains, the 11ax knobs), and the
  **Radio** form shows each radio's current channel / width / power / mode before you change it. (A keyed
  SSID's passphrase stays masked in the config, so editing one still means setting a new key.)
- Organize devices into **sites** and **groups**; run **bulk** inventory/backup across org/site/group scopes.
- **Agent auto-update (opt-in).** An on-prem agent can follow new releases on its own — it fires only when its
  image tag *moves*, is scoped to the agent alone, and **drains** the running job before the swap so a restart
  never interrupts work. See [Running in production](/production/).
- **Active/standby agents per site.** Enrol a second agent on a site's LAN and the two become a redundant
  pair: exactly one — the primary — runs each device's unattended task, so a backup capture never runs twice,
  and if the primary goes offline the standby takes over the next dispatch on its own. The primary is the
  connected agent whose id sorts first, so you choose it by naming (e.g. `site-a-01` ahead of `site-a-02`);
  election and failover are the gateway's, and the agents never talk to each other. This governs the
  background poller and scope-targeted bulk operations — the unattended work; a single-device console
  operation still runs on the agent you pick. A **durable job** queued to a primary that then drops is moved
  to the standby and dispatched there, rather than waiting for the primary to return; and **adopting the same
  access point from either agent** converges to one device row, so it is never configured twice.
- A **backup destination** for the organization: one git repository every agent pushes its config history to,
  set from the console so no one has to touch an agent's machine. The token is sealed to each agent's own key
  on the way out and held encrypted at rest on both ends. **A failed push is not a failed backup** — the local
  commit is the rollback path and happens first; pending commits go out with the next capture. Because the
  gateway must be able to hand the destination to an agent that was offline when it was set (or enrolled
  later), it stores the token encrypted with `HIVEKEEPER_CRYPTO_KEY`: scope that token to the backup
  repository and nothing else.
- **Config templates** — apply a set of HiveOS CLI lines to every device in a scope in one bulk write
  (operator-level, each device re-authorized server-side, per-device outcomes); named templates saved locally.
- **Alerts** — an on-demand fleet **scan** flags APs that breach a threshold (agent offline, still cloud-managed,
  high client load, radios outside best practice). On the `postgres` profile a **background poller** also scans
  every tenant on a schedule and **delivers** breaches to configured **webhook** and **email** channels, with
  per-channel minimum-severity gating and onset/resolution dedup (it notifies when an alert starts and when it
  clears, not every poll). Channels + thresholds are managed under **Alerts → Alert delivery** (admin-gated;
  the threshold is the single server value the on-demand scan also uses), and the Alerts page shows the poller's
  currently-firing set.
- **Members & roles** (viewer / operator / admin / owner) and **agent enrollment** — under the OIDC profile.
- Durable, persisted **jobs** under the `postgres` profile.

## Not yet

Any non-HiveOS vendor (the driver SPI is ready for them). Firmware upgrade and **PPSK admin-driven key minting**
(Caminho B) both ship but are **lab/untested** until validated on real hardware — PPSK-via-RADIUS has a dedicated
[runbook](/ppsk-radius-runbook/). The project README's Roadmap tracks the current plan.

A few HiveOS features are **not exposed because no AP in the lab has running-config grammar for them**
(confirmed live on an AP230, AP410C-1 and AP630, so they can't be driven through the SSH/apply-config path
HiveKeeper uses):

- **IGMP snooping / multicast** — there is no `igmp` command, no `mgt0` sub-node, and no `show igmp`; the AP
  bridges multicast and IGMP snooping is the upstream switch's job.
- **Tunnel-policy objects** — a user profile can *reference* a `tunnel-policy <name>`, but `tunnel-policy` is not
  a top-level object you can create over SSH (L3 VPN tunneling, out of scope for a standalone AP).
- **Individual PPSK keys** and **MAC-policy rules** — see Wi-Fi (PPSK) and Policy; MAC rules go through Advanced
  raw-CLI as the combined rule line is rejected.

Not modelled yet, and confirmed present on Wi-Fi 6 hardware: the radio-profile knobs `zerowait-dfs`,
`dynamic-channel-width`, `radio-balance`, `vht-2g`, `primary-channel-offset`, `beacon-period` and
`interference-map`, plus the `Agg0` (link aggregation) and `Red0` (redundancy) interfaces. These go through
Advanced raw-CLI in the meantime.

:::note[Device limits are not platform limits.]
Some of the ceilings above belong to a particular AP rather than to HiveOS. An AP230 rejects 160 MHz channel
width and has fewer than four spatial streams; an AP630 or AP410C-1 accepts both. HiveKeeper offers the full
grammar and lets the device refuse what it cannot do, rather than hiding a capability your newer hardware has.
:::
