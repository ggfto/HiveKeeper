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
- **Also changing the admin password ON the AP** is implemented in `HiveOsDriver.adminPasswordCommands`
  (`admin root-admin <user> password …`) and **enabled** — the grammar AND the password policy (8–32 chars,
  ≥1 digit, ≥1 uppercase, ≠ username/'password') were **confirmed live on an AP230** with an end-to-end change
  test. The UI toggle is confirm-gated (a wrong value can lock out the AP, recoverable by reset).
- **Acceptance met**: unit-tested offline (vault written, no plaintext on the wire, gateway leaks nothing) and
  the on-AP admin-password change validated end-to-end against the live AP230.

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

Makes the new `radioAdvisories` warnings *actionable*. **SHIPPED** — channel width, client target power,
band-steering, client load-balancing and the per-profile max-client cap are live in the **Radio** section (a
`radioProfileCommands` builder + the `RadioProfileForm` organism, both unit-tested; `tx-power-control` added to
the existing `RadioForm`), and **slow-rate pruning** ships as a minimum-data-rate picker in the **Wi-Fi**
section (`minRateCommands` + a block in `WifiSection`). The channel-width advisory renders next to the profile
control. The rate-set grammar AND a generated line were **confirmed live on the AP230** (`ssid <name>
11g-rate-set 12-basic 18 24 36 48 54` applied to running-config and reverted). Note the HiveOS split confirmed
on the AP230: the
`interface wifiN radio` menu has `channel`, `power`/`txpower`, `tx-power-control`, `range`, `rx-sop`,
`ed-threshold`, `antenna`, `dfs-backup-channel` — but **channel width and most density knobs live on the
named radio profile** (`radio_ng0` 2.4 GHz, `radio_ac0` 5 GHz) that interfaces reference.

- ✅ **Channel width** — `radio profile <name> channel-width 20|40|80`. The form surfaces the blast radius (a
  profile may be shared across interfaces/APs — wider than per-interface channel) and shows the width advisory.
- ✅ **Client target power** — `interface wifiN radio tx-power-control <1-20|auto>` (addresses AP↔client
  asymmetry / sticky clients).
- ✅ **Band-steering** — `radio profile <name> band-steering` (push dual-band clients to 5 GHz).
- ✅ **Client load-balancing** — `radio profile <name> client-load-balance` (spread clients across hive members).
- ✅ **Drop slow basic rates** — `ssid <name> 11g-rate-set` / `11a-rate-set` (kill 1/2/5.5/11 Mbps 11b rates that
  hog airtime — a large high-density win). A minimum-data-rate picker in the Wi-Fi section; the lowest kept rate
  becomes the only `-basic` (mandatory) rate, dropping everything slower from the air. Confirmed live on the AP230.
- ✅ **Max clients (per profile)** — `radio profile <name> max-client`. (`ssid <name> max-client` belongs with
  the per-SSID hardening in Phase 2.)

---

## Phase 2 — Wi-Fi & security (largest functional gap) — **SHIPPED**

Was WPA2-PSK only. The AP230 supports a full suite (`security-object <so> security protocol-suite ?`), now exposed.

- ✅ **Security suite picker** — `open`, `wpa2-aes-psk`, **`wpa3-sae`** ship now: a `security` field on `SsidSpec`
  drives the typed configure-ssid path (so the passphrase stays sealed/redacted), the **Add SSID** form has a
  suite selector (passphrase hidden for `open`), and an edit preserves the SSID's existing suite. The
  `protocol-suite` grammar was confirmed live on the AP230 (`wpa3-sae`/`open` take `ascii-key`/no-key
  respectively). The 802.1X suites (`wpa2-aes-8021x`, **`wpa3-aes-8021x-std`**) need a RADIUS server and land
  with the 802.1X/RADIUS work below.
- ✅ **802.1X / RADIUS** — the enterprise suites (`wpa2-aes-8021x`, `wpa3-aes-8021x-std`) ship via the typed
  `SsidSpec` path: a nested `RadiusSpec` (server + shared-secret + optional auth-port) emits
  `security-object <so> security aaa radius-server primary <ip> shared-secret <secret>`. The shared secret is
  masked by `Secrets` (now covers `shared-secret`) and encrypted at rest in durable jobs. The **Add SSID** form
  swaps the passphrase field for RADIUS server + secret when an enterprise suite is picked; 802.1X SSIDs are
  edited by remove + re-add. Grammar confirmed live on the AP230. (Backup1-3 / accounting / keepalive are a
  later tuning pass.)
- ✅ **PPSK (Private PSK)** — the **self-registration model** ships (Caminho A): a HiveAP serves PPSK and hosts the
  enrolment portal, so users register and the AP issues + stores the per-user key locally (in `users.txt`, which the
  backup captures). The PPSK block in the Wi-Fi section emits, all confirmed live: `[no] security-object <so>
  security private-psk` (+ `external-server`, `default-psk-disabled`, `ppsk-server <mgt0-ip>`), `[no] security-object
  <so> ppsk-web-server` (+ `https`, `web-directory <d>`, `auth-user` to authenticate registrants against RADIUS), and
  `ssid <so> user-group <group>` — via apply-config. **HiveKeeper does NOT mint individual keys**: HiveOS has no
  running-config grammar to create a key (`… private-psk user …` is rejected — confirmed live); keys come from user
  self-registration or an external server. Admin-driven key minting is **Caminho B** below.
- ✅ **Per-SSID hardening/tuning** — `hide-ssid`, `max-client`, **`schedule`**, `dtim-period`,
  `inter-station-traffic` (surfaced as **client isolation**, its inverse), `rrm` (802.11k), `wnm` (802.11v) ship
  as a Hardening block in the Wi-Fi section (`ssidHardeningCommands`, apply-config). Grammar confirmed live.

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
- **PPSK admin-driven key management (Caminho B)** — let an operator mint per-user private PSKs from HiveKeeper
  itself, rather than relying on end-user self-registration (Phase 2's Caminho A). HiveOS exposes **no
  running-config grammar to create an individual key over SSH** (confirmed live on the AP230: `security-object
  <so> security private-psk user …` is rejected — that path is HiveManager's proprietary channel). The clean,
  north-star-aligned way to add it is to make the **on-prem agent run/own a RADIUS server** (e.g. FreeRADIUS)
  that stores the user↔PSK mappings, and point the AP at it via `aaa ppsk-server radius-server primary <ip>` /
  `security-object <so> ppsk-web-server auth-user` / `security-object <so> security private-psk radius-auth`
  (all grammar already confirmed on the AP230). HiveKeeper would then own a key CRUD (generate/rotate/revoke a
  user's PSK) in its DB and provision RADIUS — a **new subsystem (a RADIUS-managing component), not a guided
  form**, so it lands as its own phase. The agent-as-RADIUS placement keeps secrets on-prem, consistent with the
  sealed-credential model. (Writing keys directly into the AP's local `users.txt` over SSH is **not** a
  supported/safe path — those live in a separate TPM store, not the replayable running-config.)

---

## Suggested sequencing

Phase 0 first (it unblocks reliable management and is the operator's most-felt gap), then Phase 1 (cheap, and
it completes the latency story already surfaced by the radio advisories), then Phases 2–5 by demand. Each
phase ships per-feature: a tested `lib` builder + an organism + a `capabilities.md` update.
