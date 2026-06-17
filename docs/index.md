---
title: Introduction
description: Open-source, cloud-free management for Aerohive / Extreme HiveOS access points over SSH.
hero:
  title: HiveKeeper
  tagline: Manage Aerohive / Extreme HiveOS access points standalone over SSH — no vendor cloud.
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
**Early development (v0.1).** The first milestone is a CLI that inventories and git-backs-up a single AP
over SSH, validated against a live AP230. A web control panel, an on-prem agent, and a multi-tenant
gateway are also built and run locally today — see [Capabilities](/capabilities/).
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
