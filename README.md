# HiveKeeper

Open-source tooling to manage **Aerohive / Extreme Networks HiveOS (IQ Engine)** access points
— AP230 / AP250 / AP630 (AP410 later) — **standalone over SSH, with no vendor cloud**
(no HiveManager / ExtremeCloud IQ, no license required).

These access points run their full control plane on-device and are fully manageable via the SSH CLI:
inventory, config backup/restore, firmware, SSID/VLAN, and Hive/mesh — all without phoning home.
HiveKeeper turns that CLI into a clean, scriptable, eventually-GUI tool.

> Status: **early development (v0.1)**. The first milestone is a CLI that inventories and
> git-backs-up a single AP over SSH, validated against a live AP230.

## Why

Aerohive's legacy cloud/developer API is being retired and these (now end-of-life) APs are cheap and
plentiful second-hand — but the only official management path is cloud. Prior art is thin (an archived
netmiko wrapper, an unfinished Ansible collection; no netmiko/NAPALM driver). HiveKeeper fills that gap
for homelabbers and small shops who run these APs locally.

## Architecture (one codebase, three deployment modes)

- **(A) Local** — `hive-cli` / desktop runs the engine in-process and SSHes the APs directly.
- **(B) Self-hosted server** — Spring Boot + React on `127.0.0.1` (milestone 2).
- **(C) Cloud + on-prem agent** — a multi-tenant cloud control plane dispatches *intent*; an on-prem
  agent runs the **same engine** and holds the SSH reach. Device credentials never leave the LAN. *(north star — not built yet)*

The load-bearing invariant: **`hive-core` is tenant-unaware, stateless, and transport-agnostic.**
CLI, server, and agent all invoke it through the same serializable `Command` / `Result` / `Event`
contract (`Engine.execute(Command) -> Publisher<Event>`). Local vs remote is wiring, not a fork.

### Modules

| Module | What |
| --- | --- |
| `hive-core` | Framework-free engine: `api` (Engine + DTOs), `engine` (LocalEngine), `transport` (sshj), `session` (CLI scraping), `model`, `drivers` (SPI), `spi` (EventSink/CredentialProvider/BackupStore), `tasks`. No Spring/UI/Jackson. |
| `hive-wire` | JSON (de)serialization of the core DTOs. The only module that depends on Jackson. |
| `hive-cli` | picocli front-end: `inventory`, `backup`. Talks to `Engine` + DTOs only. |

## Building

Requires a **JDK 21** (the project compiles/tests on Java 21). The Gradle wrapper is committed.

```sh
./gradlew build           # compile + test all modules
./gradlew :hive-cli:run --args="inventory 192.168.x.x"
```

The Gradle daemon may run on a newer JDK; the project pins a **Java 21 toolchain**. Local JDK locations
are configured in `gradle.properties` (`org.gradle.java.installations.paths`) — adjust those to your
machine, or add the [foojay-resolver](https://github.com/gradle/foojay-toolchains) plugin to
auto-provision a JDK 21.

## License

[Apache License 2.0](LICENSE).
