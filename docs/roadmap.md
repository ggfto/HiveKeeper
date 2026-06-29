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

### 0a. Credential management (per device / per site) — **SHIPPED**

Set or rotate a device's SSH credential from the UI; the secret is **sealed to the on-prem agent's public
key** at the gateway (end-to-end, so the cloud never holds a usable plaintext) and written to the agent's
local vault, **encrypted at rest**. As built:

- `CredentialProvider` gained a writable variant (`WritableCredentialProvider`) + a `SecretUnsealer` SPI;
  the agent's `WritableVaultCredentialProvider` encrypts passwords at rest (`HIVEKEEPER_VAULT_KEY`) and still
  reads legacy plaintext.
- New `Command.SetCredential` / `Result.CredentialSet` flow through the existing engine/agent pipeline.
  `EnvelopeCipher` (RSA-OAEP + AES-GCM, `env1:` token; `plain1:` dev fallback) lives in `hive-core.crypto`
  alongside the promoted `SecretCipher`. The gateway caches each agent's public key from its mTLS cert and
  seals on `POST /api/agents/{id}/set-credential` (admin) — **synchronous, never a durable job, never
  persisted, never logged**. A `CredentialForm` section drives it.
- **Also changing the admin password ON the AP** is built behind a driver seam
  (`Driver.adminPasswordCommands`) but stays **disabled** until the HiveOS grammar is confirmed live (project
  rule: never guess CLI). The UI toggle is gated accordingly.
- **Acceptance met** offline (unit-tested: vault written, no plaintext on the wire, gateway leaks nothing);
  live rotation against the AP230 + enabling the on-AP change remain to validate when the AP is reachable.

### 0b. Adoption with identification — **SHIPPED**

- **Identity probe in discovery** — an **Identify** action per discovered host runs an inventory through the
  agent; a success means a reachable HiveOS AP (and its model), a failure means it did not identify as one.
- **Support classification** — `supportLevel` (a pure lib) maps a model to a badge: **tested** (AP230 / AP250
  / AP630) / **HiveOS · untested** / **unsupported**, shown on the discovered host (after Identify) and on the
  device detail header. A soft signal, not a hard block.
- **Credential prompt at adopt** — the discovered-hosts panel takes an optional username/password; on adopt the
  gateway registers the device and then sets its credential through the 0a sealed path, in one flow.
- **Acceptance met** offline (Identify + adopt-with-credential are unit-tested; the badge reflects the model).
  Live identify/adopt against the AP230 remains to validate when it is reachable.

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
