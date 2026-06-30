---
title: Introduction
description: Open-source, cloud-free management for Aerohive / Extreme HiveOS access points over SSH.
hero:
  title: HiveKeeper
  tagline: Manage Aerohive / Extreme HiveOS access points standalone over SSH — no vendor cloud.
  image:
    file: ./screenshots/overview.png
    alt: The HiveKeeper console
  actions:
    - text: Try the live demo
      link: /demo/
      icon: external
      variant: primary
    - text: Get started
      link: /getting-started/
      icon: right-arrow
      variant: minimal
---

**HiveKeeper** is open-source tooling to manage **Aerohive / Extreme Networks HiveOS (IQ Engine)**
access points — AP230 / AP250 / AP630 (AP410 later) — **standalone over SSH, with no vendor cloud**
(no HiveManager / ExtremeCloud IQ, no license required).

These access points run their full control plane on-device and are fully manageable via the SSH CLI:
inventory, config backup/restore, firmware, SSID/VLAN, and Hive/mesh — all without phoning home.
HiveKeeper turns that CLI into a clean, scriptable, eventually-GUI tool.

:::note[Status]
**Active development.** Well past the first milestone (a CLI that inventories and git-backs-up an AP): a full
web control panel, an on-prem agent, and a multi-tenant gateway run locally today, covering credentials, the
complete Wi-Fi/security surface, network policy, RF tuning, and operations (scheduling, config templates,
alerting) — every HiveOS line confirmed live on an AP230. A few paths ship **lab/untested** (firmware upgrade;
admin-minted PPSK via RADIUS). See [Capabilities](/capabilities/) and the [Roadmap](/roadmap/).
:::

## Why

Aerohive's legacy cloud/developer API is being retired and these (now end-of-life) APs are cheap and
plentiful second-hand — but the only official management path is cloud. Prior art is thin (an archived
netmiko wrapper, an unfinished Ansible collection; no netmiko/NAPALM driver). HiveKeeper fills that gap
for homelabbers and small shops who run these APs locally.

## Where to go next

- **[Getting started](/getting-started/)** — build it, then run the whole stack with one script.
- **[Capabilities](/capabilities/)** — exactly what HiveKeeper can monitor and configure today.
- **[Authentication](/authentication/)** — it runs fine without Keycloak; pick solo, tenant-key, or OIDC.
- **[Architecture](/architecture/)** — one codebase, three deployment modes, and the load-bearing invariant.
- **[Agent ⇄ gateway protocol](/agent-protocol/)** — how the on-prem agent and the cloud gateway talk.
