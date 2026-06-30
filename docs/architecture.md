---
title: Architecture
description: One codebase, three deployment modes, and the invariant that keeps them in sync.
---

## Three deployment modes, one codebase

- **(A) Local** — `hive-cli` / desktop runs the engine in-process and SSHes the APs directly.
- **(B) Self-hosted server** — `hive-server` (Spring Boot) + `hive-web` (React) on `127.0.0.1`.
- **(C) Cloud + on-prem agent** — a multi-tenant control plane (`hive-gateway`) dispatches *intent*; an
  on-prem `hive-agent` runs the **same engine** and holds the SSH reach. Device credentials never leave the
  LAN. Runs locally today; a hosted multi-tenant cloud is the north star.

The load-bearing invariant: **`hive-core` is tenant-unaware, stateless, and transport-agnostic.**
CLI, server, and agent all invoke it through the same serializable `Command` / `Result` / `Event`
contract (`Engine.execute(Command) -> Publisher<Event>`). Local vs remote is wiring, not a fork.

```mermaid
flowchart TD
    CLI["hive-cli — mode A"]
    SRV["hive-server — mode B"]
    GW["hive-gateway — control plane"]
    AG["hive-agent — mode C"]
    E["hive-core: Engine.execute(Command) → Event / Result"]
    APs[("HiveOS APs")]

    CLI --> E
    SRV --> E
    GW -->|"Frame over outbound WSS:443"| AG
    AG --> E
    E -->|SSH| APs
```

## Modules

| Module | What |
| --- | --- |
| `hive-core` | Framework-free engine: `api` (Engine + DTOs), `engine` (LocalEngine), `transport` (sshj), `session` (CLI scraping), `model`, `drivers` (SPI), `spi` (EventSink / `CredentialProvider` + writable variant / `SecretUnsealer` / `PpskUserStore` / BackupStore), `crypto` (AES-GCM `SecretCipher` for at-rest, `EnvelopeCipher` for sealing a secret to an agent's public key, `PskGenerator`), `alerts` (the pure alert / radio-advisory rule engine, shared verbatim with the web), `tasks`. No Spring/UI/Jackson. |
| `hive-wire` | JSON (de)serialization of the core DTOs. The only module that depends on Jackson. |
| `hive-protocol` | The serializable gateway↔agent protocol; carries the core `Command` / `Result` / `Event` DTOs verbatim so local and remote are the same contract. |
| `hive-cli` | picocli front-end: `inventory`, `backup`, and the config commands. Talks to `Engine` + DTOs only. |
| `hive-server` | Spring Boot REST server (**mode B**): runs the engine in-process and SSHes APs directly. Localhost `:8080`. |
| `hive-agent` | On-prem agent (**mode C**): dials out to the gateway over WebSocket, runs the **same engine**, and holds the SSH reach to the LAN. Device credentials never leave it. |
| `hive-gateway` | Multi-tenant control plane (**mode C**): dispatches *intent* to enrolled agents, REST API on `:8090`. Optional `postgres` (RLS) and `oidc` profiles — see [Authentication](/authentication/). |
| `hive-web` | The web UI (Vite + React). **Not** a Gradle module — a standalone pnpm project. **Gateway-only**: every page talks to `hive-gateway` through the `/gw` proxy (solo single-AP and multi-org are gateway run-modes, not separate UIs). |

## How a request flows in mode C

The on-prem agent lives on a private LAN behind NAT/firewall and holds the SSH reach to the APs. The
cloud cannot connect inward, so the **agent dials out** and the cloud only ever *responds* on that
connection. The wire payload is the **already-serializable in-process API**, so the agent literally does
`engine.execute(decode(frame))`. The full transport, framing, and resilience design is documented in the
[agent ⇄ gateway protocol](/agent-protocol/).

## Background work in the gateway

Beyond request/response, the `postgres`-profile gateway runs one scheduled job: the **fleet alert poller**
(`FleetPoller`, `@Scheduled`). It walks every tenant on an interval, reaches each device through its agent
exactly like a bulk inventory, evaluates the shared `hive-core` `alerts` rules, and diffs the result against a
`fleet_alert` state table so an alert is delivered on **onset** and again on **resolution** — never every poll.
Delivery fans out to per-tenant **webhook** and **email** channels. It is the only background scheduler;
operator-initiated durable jobs use the separate `JobGateway` redelivery path, not the scheduler.
