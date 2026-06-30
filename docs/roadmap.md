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

## Phase 3 — Network policy & L2/L3 — **SHIPPED**

Was: VLAN read from config but not edited beyond the SSID's optional VLAN. Now a full policy/L2-L3 surface, all
grammar **confirmed live on the AP230** (with two write quirks the live probes caught: HiveOS **rejects a
combined** `user-profile <n> attribute 99 vlan-id 99` — each setting is its own line, the running-config later
coalescing them; and firewall/QoS objects must be **created before** they take a rule).

- ✅ **User-profiles & VLANs** — the **Policy** section lists the user profiles read from the running-config and
  lets an operator create/overwrite one (default **VLAN id** or **VLAN group**, optional **QoS policy** +
  **schedule**, keyed by a numeric **attribute** 0–4095), **bind** it to an SSID's security object
  (`security-object <so> default-user-profile-attr <attr>`), and remove it (`userProfileCommands` /
  `bindUserProfileCommands` / `removeUserProfileCommands` + `parseUserProfiles`).
- ✅ **Per-profile policy** — a bandwidth SLA (`performance-sentinel enable | guaranteed-bandwidth | action`),
  **L2/L3 firewall** bindings + default actions (`security ip-policy from-access|to-access`,
  `ip-policy-default-action`, the MAC equivalents) and a `qos-marker-map 8021p|diffserv` (`userProfilePolicyCommands`).
- ✅ **Firewall policy objects** — `ip-policy <name>` (create + a `permit|deny|nat|redirect` rule on a service
  between from/to) and `mac-policy <name>` (create; MAC rules via Advanced raw-CLI — the combined line is rejected)
  (`ipPolicyCommands` / `macPolicyCommands` + `parseFirewallPolicies`).
- ✅ **QoS** — a `qos policy <name>` rate-limit object (`qos policy <n> user-profile <kbps> <weight>`, `qos enable`),
  referenced by a profile's QoS field (`qosPolicyCommands` + `parseQosPolicies`); and **per-SSID** `qos-classifier` /
  `qos-marker` profile bindings + `wmm` toggle for voice/video priority (`ssidQosCommands`, in the Wi-Fi section).
- ✅ **LLDP** — device-wide `lldp` enable + `timer` / `holdtime` / `receive-only` / `max-entries` (`lldpCommands`,
  in the Network section). HiveOS has no per-interface LLDP (`interface mgt0 lldp` is rejected).
- ✅ **Static routes** — `ip route net <ip> <mask> gateway <gw> [metric]` / `ip route host <ip> gateway <gw>`,
  listed + added + removed (confirm-gated; the gateway must be on a connected subnet) (`staticRouteCommands` +
  `parseStaticRoutes`, in the Network section).
- ⛔ **Multicast / IGMP snooping** — **not exposed**: the AP230 has no `igmp` command, no `mgt0` sub-node, and no
  `show igmp` (confirmed live). The AP bridges multicast; IGMP snooping is the upstream switch's job. **Tunnel
  policy** is likewise referenced-only (no top-level object grammar) — see `capabilities.md` → *Not yet*.

---

## Phase 4 — RF tuning & high-density (advanced) — **SHIPPED**

Radio knobs for dense RF environments, behind an **Advanced RF tuning** disclosure in each radio form. All
grammar was **confirmed live on the AP230** via `?` context help — and it caught the HiveOS split between
where knobs live: **interface-level** (`interface wifiN radio …`) vs **profile-level** (`radio profile <name> …`).

- ✅ **Interface-level** (added to `radioCommands`, surfaced in `RadioForm`): `rx-sop <number|high|low|medium>`
  (receiver start-of-packet detection — dBm or a density preset), `ed-threshold <-70..-50>` (energy-detect
  threshold), `dfs-backup-channel <freq|channel>` (the 5 GHz fallback when radar forces a channel switch).
- ✅ **Profile-level** (added to `radioProfileCommands`, surfaced in `RadioProfileForm`): `dfs`,
  `short-guard-interval`, `ampdu`, `amsdu`, `frameburst` (bare-line toggles, `no …` to disable);
  `high-density enable` and `weak-snr-suppress enable` (toggles whose positive form carries the `enable`
  sub-word, so the negation is `no … enable`); `tx-beamforming auto|explicit-only` (`no …` to disable);
  `phymode 11a|11ac|11b/g|11na|11ng`; and `receive-chain` / `transmit-chain <1-3>`.
- **Acceptance met**: the two builders and both organisms are unit-tested, and the knobs were **applied and
  reverted live on the AP230** (no `save`, non-persistent) — interface knobs on `wifi1`, profile knobs on
  throwaway custom profiles `HKTEST*` (created, confirmed in `show running-config`, removed with
  `no radio profile …`). `capabilities.md` updated.
- **Live write quirks the apply test caught** (and why probing beats `?`-only confirmation):
  - **Default radio profiles are read-only.** `radio profile radio_ac0 …` (and `radio_ng0`) is rejected with
    *“can't configure default radio profile radio_ac0!”* — this hits Phase 1's channel-width too. The knobs
    only apply to a **custom** profile, which the first knob line **auto-creates**. `RadioProfileForm` warns
    when the chosen name looks like a factory default, and a **Bind to radio** selector closes the loop: it
    appends `interface wifiN radio profile <name>` as the last line so the configured custom profile actually
    takes effect (confirmed live: create+configure `HKBIND`, `interface wifi1 radio profile HKBIND`, revert by
    re-binding `radio_ac0` + `no radio profile HKBIND`). Binding is warned (it swaps the radio's whole profile
    and briefly drops its wireless clients; mgt0 is unaffected) and flags a band mismatch (an 11ac profile
    bound to the 2.4 GHz radio).
  - **`phymode` must precede `channel-width` and `tx-beamforming`.** A fresh profile is `11b/g`; both reject as
    *“inconsistent with current phymode”* / *“incompatible PHY mode … configure PHY mode first”* until phymode
    matches. `radioProfileCommands` therefore emits `phymode` first.

---

## Phase 5 — Operations & lifecycle

- **Firmware upgrade GA** — validate the `save image` path on real hardware, move it out of lab/untested.
- **Scheduling** — global `schedule <name>` objects reused by SSIDs and user-profiles. **SHIPPED (objects):** a
  **Schedules** section (`scheduleCommands` / `removeScheduleCommands` builders + `parseSchedules` + the
  `ScheduleSection` organism, all unit-tested) creates/lists/removes **recurrent** (`weekday-range` and/or
  `time-range`, optional `date-range`) and **one-time** (`once <date> <time> to <date> <time>`) schedules; both
  forms were **applied to the running-config and reverted live on the AP230**. SSID hardening (Phase 2) and
  user-profiles (Phase 3) already reference these by name.
- **Scheduled reboot — SHIPPED.** A confirm-gated **Scheduled reboot** control in the Power section
  (`rebootScheduleCommands` / `cancelRebootScheduleCommands` builders + `parseRebootSchedule` + the
  `ScheduledRebootForm` organism, all unit-tested) sets a recurring `reboot schedule daily every <n> day(s) time
  <hh:mm:ss>` / `weekly every <n> week(s) <Weekday> time <hh:mm:ss>`, reads the next reboot from `show reboot
  schedule`, and cancels with `no reboot schedule`. **A decisive live quirk justified probing over `?`-help:**
  the recurring `reboot schedule` forms are **non-interactive** (apply-config can drive them — validated by
  scheduling a future reboot, confirming via `show`, then cancelling, with no actual reboot), but `reboot date`
  and `reboot offset` prompt **"Do you really want to reboot? (Y/N)"** which would hang the exec channel — so
  only the recurring forms are exposed. The schedule is not part of the running-config (`save: false`).
- **Config templates — SHIPPED.** Apply a set of HiveOS CLI lines (a template) to every device in a scope
  (org / site / group) in one bulk **write**. New gateway endpoint `POST /api/fleet/bulk/apply-config`
  (`BulkApplyConfigRequest` → `doBulkApplyConfig`) mirrors the read-only bulk path but requires **OPERATOR**,
  validates/normalizes the command list server-side, and **re-authorizes each device at operator level against
  its own lineage** before touching it (a cross-site group can carry a foreign-site device) — reusing the same
  per-device outcome model (ok / failed / agent_offline / skipped / forbidden / timeout) and wall-clock budget.
  UI: a **Config templates** section on the Bulk ops page (`ConfigTemplatePanel`) with a scope picker, a CLI
  editor, a `save config` toggle, a confirm gate (it writes to many APs), and the per-device outcomes table;
  named templates are saved locally (`configTemplate.js` — `parseTemplateCommands` + localStorage CRUD). All
  unit-tested (gateway security slice + web lib/organism/client).
- **Alerting / thresholds — SHIPPED (in-console).** An **Alerts** page runs an on-demand fleet scan (each
  device's agent online-state + a live inventory read) and evaluates it against threshold rules, listing only
  the APs that breach one: agent offline, **still cloud-managed** (CAPWAP up — HiveKeeper's signature), high
  client load (a configurable per-AP cap), and radios outside best practice (reusing `radioAdvisories`). A pure
  `alerts.js` rules engine (`evaluateAlerts` + `worstSeverity` + locally-persisted thresholds) feeds both the
  fleet scan (`FleetAlertsPanel`, which re-evaluates the held snapshots live as you edit a threshold) and can
  feed the device Monitoring tab. All unit-tested. **Deliberately deferred:** a background poller and an
  external **delivery channel** (email / webhook) — those need a server-side scheduler + notifier subsystem;
  today alerting is an on-demand, in-console view.
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
