---
title: PPSK via RADIUS — live validation runbook
description: The lab steps that take PPSK Caminho B from "built + unit-tested" to "live-confirmed", including the one remaining unknown — the Aerohive user-profile VSA — that must be captured from a real exchange.
---

> **Status: the subsystem is built and unit-tested; this runbook is the live-validation pass.** Everything in
> [PPSK via RADIUS — design](ppsk-radius-design.md) is implemented: the sealed `ManagePpskUser` pipeline, the
> agent's encrypted PPSK store, the gateway key CRUD + REST, the web UI, and the FreeRADIUS provisioner. Two
> things can only be confirmed against real hardware + a real client, so they live here, not in CI:
>
> 1. the **end-to-end auth path** (an AP forwards a Private PSK to FreeRADIUS, gets Access-Accept + the VLAN);
> 2. the exact **Aerohive user-profile VSA** for returning a named user-profile on Accept (the design's last
>    open question — the RFC VLAN tunnel attributes are already emitted; the VSA is currently a documented
>    comment in the generated authorize file).
>
> Until this pass completes, PPSK Caminho B ships **"untested"**, exactly like firmware upgrade.

## Prerequisites

- The lab **AP230** (`192.168.1.101`, `admin/Aerohive1`) on the same LAN as the agent host.
- **Podman** on the agent host (the dev box has it; `docker` users substitute `docker`).
- A **throwaway security-object / SSID** — never the production one. PPSK-via-RADIUS changes an SSID's
  authentication path; a misconfig can lock out live clients. This mirrors the firmware-upgrade caution.
- A Wi-Fi **client** (phone/laptop) to associate with a per-user PSK.

## 1. Stand up FreeRADIUS co-located with the agent

```powershell
powershell -File scripts/dev-radius.ps1 -Secret testing123
# note the RadiusDir it prints, then run the agent pointed at it:
$env:HIVEKEEPER_PPSK_STORE = "<RadiusDir>\ppsk.properties"
$env:HIVEKEEPER_RADIUS_DIR = "<RadiusDir>"
$env:HIVEKEEPER_VAULT_KEY  = "<base64 AES-256>"   # encrypts the PPSK store at rest
```

The agent's `FilePpskUserStore` writes `<RadiusDir>\authorize` (FreeRADIUS files module) on every
create/rotate/revoke; `FreeRadiusFilesProvisioner` renders it. After a change, reload FreeRADIUS:
`podman kill -s HUP hivekeeper-radius`.

## 2. Point the throwaway security-object at RADIUS (apply + revert, non-persistent)

All grammar below is **already confirmed live** (see the design doc). Apply against a **test** SO:

```text
aaa ppsk-server radius-server primary <agent-ip> shared-secret testing123
security-object HKB security private-psk
security-object HKB security private-psk radius-auth pap
ssid HKB user-group ppsk-users
```

Drive this from HiveKeeper's **PPSK via RADIUS** block (Wi-Fi section) — it emits exactly these lines through
`apply-config`. Do **not** `save`; revert at the end with the `no …` forms.

## 3. Mint a user from HiveKeeper and connect a client

1. In the device's **PPSK users** tab, mint a user (security object `HKB`, a username, VLAN if testing VLANs).
   Copy the one-time PSK shown.
2. Confirm the agent wrote it: `<RadiusDir>\authorize` now has `<username> Cleartext-Password := "<psk>"`
   (+ the `Tunnel-*` block if a VLAN was set). `podman kill -s HUP hivekeeper-radius`.
3. Associate the client to the `HKB` SSID using that PSK.

## 4. Capture the real exchange (this is what fills the last unknown)

With the client associating, watch FreeRADIUS and capture the Access-Request/Accept:

```bash
podman exec -it hivekeeper-radius radiusd -X     # or: podman logs -f hivekeeper-radius
# in another shell, a controlled check:
podman exec -it hivekeeper-radius radtest <username> <psk> 127.0.0.1 0 testing123
```

Record, for the design doc + the provisioner:

- **Does PAP match?** The AP should send the submitted PSK as the User-Password; FreeRADIUS matches
  `Cleartext-Password` and returns **Access-Accept**. If the AP uses CHAP/MS-CHAP-v2 instead, switch
  `radius-auth` to match and note it.
- **The VLAN reply** — confirm the AP honours `Tunnel-Private-Group-Id` (the client lands on the VLAN).
- **The Aerohive user-profile VSA** — if returning a *named user-profile* (not just a VLAN) is required,
  capture the exact VSA name/number the AP expects on Accept (e.g. from `radiusd -X` decoding an Accept the AP
  accepts, or Aerohive/Extreme dictionary docs). **This is the value to wire into
  `FreeRadiusFilesProvisioner` — it is currently emitted only as a comment.**

## 5. Rotate, revoke, and revert

- **Rotate** the user in the UI → confirm the client must re-key with the new PSK (old one rejected after HUP).
- **Revoke** → confirm the user disappears from `authorize` and the client is dropped / cannot re-auth.
- **Revert the AP**: `no security-object HKB security private-psk radius-auth`,
  `no aaa ppsk-server radius-server primary`, and remove the throwaway SO — non-persistent, no `save`.

## 6. Promote out of "untested"

Once the Access-Accept + VLAN are confirmed and the Aerohive VSA is captured and wired in:

- update [`FreeRadiusFilesProvisioner`](../hive-agent/src/main/java/io/hivekeeper/agent/radius/FreeRadiusFilesProvisioner.java)
  to emit the confirmed VSA (replacing the comment), with a unit test pinning the rendered line;
- record the captured grammar in [PPSK via RADIUS — design](ppsk-radius-design.md) → *Open questions*;
- flip the capabilities/roadmap note from "untested" to "live-confirmed".
