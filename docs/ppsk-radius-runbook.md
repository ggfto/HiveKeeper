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
create/rotate/revoke; `FreeRadiusFilesProvisioner` renders it. On the Windows dev box re-sync after a mutation
with `scripts/dev-radius.ps1 -Reload` (re-copies the file and HUPs the server); on Linux bind-mount the dir and
just `podman kill -s HUP hivekeeper-radius`.

> **Confirmed live (server side):** the `authorize` format `FreeRadiusFilesProvisioner` emits was validated
> against FreeRADIUS 3.2 — `radtest <user> <psk> 127.0.0.1 0 <secret>` returns **Access-Accept** with
> `Tunnel-Type=VLAN` / `Tunnel-Medium-Type=IEEE-802` / `Tunnel-Private-Group-Id=<vlan>`, and a wrong PSK
> returns **Access-Reject**. So PAP-match-of-the-PSK + the RFC-2868 VLAN reply work end-to-end on the RADIUS
> server; what step 4 still confirms is the AP↔RADIUS half and the Aerohive VSA.
>
> Gotchas the dev script already handles (don't hand-roll the container): the daemon binary is
> `/usr/sbin/freeradius` (not `radiusd`); FreeRADIUS refuses a **globally-writable** config (Windows
> bind-mounts are 0777 — the script `cp`s + `chmod o-w`s instead); and config/authorize files must be written
> **without a UTF-8 BOM** (PowerShell's `Set-Content -Encoding utf8` adds one and FreeRADIUS won't parse it —
> the agent writes BOM-free).

> **Networking note (Windows + Podman):** the AP must reach the host's `1812/udp`. Find the host's LAN IP with
> `Find-NetRoute -RemoteIPAddress <ap-ip>`. With a WSL2-backed Podman, inbound LAN traffic to a published port
> is not reachable by default — allow `1812/udp` through Windows Firewall and confirm the port is forwarded into
> the Podman machine (or run FreeRADIUS where the AP can route to it). `radtest` from the host validates the
> server regardless of this.

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
