---
title: Configuration roadmap
description: A phased plan to grow HiveKeeper from today's surface to full HiveOS AP management.
---

This is a from-scratch, tier-by-tier plan for everything HiveKeeper could let an operator configure on a
HiveOS AP that it does not expose today. It expands the phase summary in the project README with concrete,
grammar-confirmed work. All CLI grammar below was confirmed live on an **AP230 (HiveOS 10.6r1a)** via `?`
context help (`scripts/hk-cli-explore.py`), not guessed.

## Principles (apply to every phase)

- **TDD + atomic design** for `hive-web` — pure logic (CLI builders, parsers, advisories) lives in `src/lib`
  with unit tests first; UI is atoms → molecules → organisms.
- **`hive-core` stays vendor-agnostic** — for now the web UI generates HiveOS CLI and pushes it through
  `apply-config`; this UI-side grammar can later move into a `hive-core` driver generator for multi-vendor.
- **North-star invariants** — secrets never persist in the cloud gateway (only a `credRef` does); the core
  engine is tenant-unaware and transport-agnostic.
- **Danger discipline** — any command that can drop AP connectivity warns + confirms (the pattern in
  `NetworkForm` / `ClientModeForm`).
- Commits and docs in **English**.

## Baseline — what exists today

Configurable now: hostname, Radio (channel / power / mode **only**), Mesh/Hive + tuning, Captive portal,
CAPWAP standalone, Network (mgmt IP / VLAN / gateway / DHCP-client / DNS / NTP), Client-mode, LED, SNMP,
Syslog, Wi-Fi (WPA2-PSK SSID create/edit/remove + VLAN), Advanced raw-CLI, Backup/Restore, Firmware upgrade
(lab/untested). Read-only: inventory, clients, live radio status (`show acsp`), CAPWAP state, on-AP log, map,
audit log. Adoption: SSH-banner discovery → adopt (probes `show version`, requires a serial).

> **Doc accuracy fix:** `capabilities.md` claims Radio configures "band, channel, width, Tx power", but
> `RadioForm` only sets channel/power/mode. Channel **width** and **band** are not configurable yet — they
> live on the radio *profile* (see Phase 1). Correct `capabilities.md` when Phase 1 lands.

---

## Phase 0 — Foundation: credentials & trustworthy adoption

The two gaps that block everything else: there is no place to manage AP credentials, and discovery cannot
tell an AP from any other SSH host.

### 0a. Credential management (per device / per site)

- **Today:** credentials resolve on-prem (`CredentialProvider` → `DefaultCredentialProvider` global, or
  `VaultCredentialProvider` properties file). The cloud only carries an opaque `credRef`; the vault is
  hand-edited on the agent host and `credRef` is manually synced. No UI/API.
- **Build:** make `credRef` a managed entity with a UI form to set/rotate a device's (or site's) credentials.
  - **Mode B (`hive-server`, no split):** local encrypted credential store + form — straightforward.
  - **Mode C (gateway + agent):** **passthrough, never persisted in the cloud.** The operator types the secret
    in the UI; the gateway encrypts it to the **agent's public key** (the README's planned "end-to-end secret
    encryption to the agent's public key") and forwards it over the existing WebSocket; the agent decrypts and
    writes its local vault (encrypted at rest). The gateway never stores or logs the secret.
  - Touch points: `CredentialProvider` SPI (add a writable variant), agent vault write path, a new
    protocol message `SetCredential`, `GatewayController`, and a `CredentialForm` organism in `hive-web`.
- **Acceptance:** rotate a device password entirely from the UI; gateway DB/logs contain no plaintext secret.

### 0b. Adoption with identification

- **Today:** `TcpBannerScanner` only reads the generic SSH banner — it cannot distinguish a HiveOS AP.
  `HiveOsDriver.recognizes()` (matches `hiveos` / `iq engine` / `aerohive` in `show version`) is the real
  check, but only runs at adopt. No model allowlist.
- **Build:**
  1. **Identity probe in discovery** — after the TCP banner, optionally run an authenticated `show version`
     (agent default cred) and tag each host as "HiveOS AP (model X)" vs "other SSH host".
  2. **Support classification** — surface model + a badge: **tested** (AP230 / AP250 / AP630) / **HiveOS,
     untested** / **unsupported**, in both the adopt result and the device detail. A soft allowlist, not a
     hard block.
  3. **Credential prompt at adopt** — let the operator supply a credential during adoption (feeds 0a) instead
     of silently using the agent default and failing later.
- **Acceptance:** the discovered-hosts list shows which hosts are adoptable APs *before* adopting; adopting a
  non-default-credential AP works in one flow.

---

## Phase 1 — Radio completeness (closes the latency/density story)

Makes the new `radioAdvisories` warnings *actionable*. Note the HiveOS split confirmed on the AP230: the
`interface wifiN radio` menu has `channel`, `power`/`txpower`, `tx-power-control`, `range`, `rx-sop`,
`ed-threshold`, `antenna`, `dfs-backup-channel` — but **channel width and most density knobs live on the
named radio profile** (`radio_ng0` 2.4 GHz, `radio_ac0` 5 GHz) that interfaces reference.

- **Channel width** — `radio profile <name> channel-width 20|40|80`. Read which profile each `wifiN` uses and
  show its blast radius (a profile may be shared across interfaces/APs — wider than per-interface channel).
- **Client target power** — `interface wifiN radio tx-power-control <1-20|auto>` (addresses AP↔client
  asymmetry / sticky clients).
- **Band-steering** — `radio profile <name> band-steering` (push dual-band clients to 5 GHz).
- **Client load-balancing** — `radio profile <name> client-load-balance` (spread clients across hive members).
- **Drop slow basic rates** — `ssid <name> 11g-rate-set` / `11a-rate-set` (kill 1/2/5.5/11 Mbps 11b rates that
  hog airtime — a large high-density win).
- **Max clients** — `ssid <name> max-client` and `radio profile <name> max-client`.

Wire the advisories (`radioAdvisories`) to suggest the exact fix and pre-fill these controls.

---

## Phase 2 — Wi-Fi & security (largest functional gap)

Today only WPA2-PSK. The AP230 supports a full suite (`security-object <so> security protocol-suite ?`).

- **Security suite picker** — `open`, `wpa2-aes-psk`, **`wpa3-sae`**, `wpa2-aes-8021x`,
  **`wpa3-aes-8021x-std`**, etc. (replace the implicit WPA2-PSK-only path).
- **802.1X / RADIUS** — `aaa radius-server` (primary / backup1-3 / accounting / keepalive). Enterprise auth.
- **PPSK (Private PSK)** — `aaa ppsk-server` + `ssid <name> user-group` + `security-object <so>
  ppsk-web-server`. The backup channel already captures `users.txt` (TPM), so this is anticipated.
- **Per-SSID hardening/tuning** — `hide-ssid`, `max-client`, **`schedule`** (guest Wi-Fi on business hours
  only), `dtim-period`, `inter-station-traffic` (client isolation), `wmm`, `rrm` (802.11k), `wnm` (802.11v).

---

## Phase 3 — Network policy & L2/L3

Today VLAN is read from config but not edited beyond the SSID's optional VLAN.

- **User-profiles & VLANs** — `user-profile <name> vlan-id | vlan-group | qos-policy | qos-marker-map |
  performance-sentinel (rate limit) | ip-policy-default-action / mac-policy (firewall) | schedule |
  tunnel-policy`. A first-class policy editor.
- **QoS** — `ssid <name> qos-classifier | qos-marker | wmm`, mapped to user-profiles (voice/video priority).
- **Multicast / IGMP snooping**, **LLDP**, additional static routes.

---

## Phase 4 — RF tuning & high-density (advanced)

Radio-profile knobs for dense RF environments, behind an "advanced" disclosure:

- `dfs` (+ `dfs-backup-channel`) to unlock clean DFS channels in 5 GHz.
- `short-guard-interval`, `ampdu` / `amsdu`, `tx-beamforming`, `frameburst`, `high-density`,
  `rx-sop` / `ed-threshold`, `phymode`, `receive-chain` / `transmit-chain`, `weak-snr-suppress`.

---

## Phase 5 — Operations & lifecycle

- **Firmware upgrade GA** — validate the `save image` path on real hardware, move it out of lab/untested.
- **Scheduling** — global `schedule <name>` objects reused by SSIDs and user-profiles; scheduled reboot.
- **Alerting / thresholds** and **config templates** (apply a profile across a site/group) — both currently
  in the "Not yet" list; they build naturally on the policy and bulk-ops foundations.

---

## Suggested sequencing

Phase 0 first (it unblocks reliable management and is the operator's most-felt gap), then Phase 1 (cheap, and
it completes the latency story already surfaced by the radio advisories), then Phases 2–5 by demand. Each
phase ships per-feature: a tested `lib` builder + an organism + a `capabilities.md` update.
