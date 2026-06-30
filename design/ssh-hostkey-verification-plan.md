# Plan B — SSH host-key verification (TOFU / known_hosts)

> **Status: SHIPPED.** Implemented as `HostKeyPolicy.TOFU` + `TofuKnownHostsVerifier` in `hive-core`, wired
> through `SshjTransport` / `HiveCore.localEngine` and the agent (`HIVEKEEPER_SSH_HOSTKEY` /
> `HIVEKEEPER_KNOWN_HOSTS`, default TOFU). Unit-tested (`TofuKnownHostsVerifierTest`); README + agent README
> updated. Original plan below for reference.

## Goal

Stop trusting any SSH host key. Verify the AP's host key on connect: **trust-on-first-use (TOFU)** records the
key the first time, then every later connect must match; a mismatch is refused (possible MITM — or a legitimately
reset/reflashed AP, which has a clear recovery path). Default the agent to TOFU; keep `ACCEPT_ALL` only as an
explicit lab escape hatch.

## Current state (what exists)

- `hive-core` already has a `HostKeyPolicy` enum (`ACCEPT_ALL`, `KNOWN_HOSTS`) and `SshjTransport` honors it:
  `ACCEPT_ALL` → sshj `PromiscuousVerifier`; `KNOWN_HOSTS` → `client.loadKnownHosts()` (the default user
  `~/.ssh/known_hosts`).
- **The gaps:** (1) the default ctor uses `ACCEPT_ALL`; (2) `KNOWN_HOSTS` has **no TOFU** (it can't add a key on
  first sight, and it reads the *user's* file, not a managed path); (3) nothing wires a non-default policy/path
  from the agent into the transport — `HiveCore.localEngine` builds `new SshjTransport()` (= ACCEPT_ALL); (4) no
  recovery action for a changed key.

So the seam exists; the work is a TOFU verifier + a managed known_hosts path + wiring + config + recovery.

## Design

### TOFU verifier

A new `HostKeyPolicy.TOFU` backed by a **managed known_hosts file** at a configured path (the agent owns the SSH
reach, so it owns the file):
- host (`host` or `host:port`) **unknown** → record the key, accept (log it as first-trust);
- host **known**, key **matches** → accept;
- host known, key **mismatches** → **reject** (sshj surfaces it) with a clear, recoverable error:
  *"AP host key changed — possible MITM, or the AP was reset/reflashed; clear its known-hosts entry to
  re-trust."*

Implementation: a custom sshj `HostKeyVerifier` wrapping `OpenSSHKnownHosts` (read/append the managed file), or a
small hand-rolled store keyed by host → fingerprint. The verifier is pure enough to unit-test by calling
`verify(hostname, port, PublicKey)` directly — no live SSH server needed.

### Wiring + config

- `SshjTransport` gains the known_hosts path alongside the policy (it already takes a `HostKeyPolicy`).
- `HiveCore.localEngine` must let the caller pass a transport/policy (today it hard-codes `new SshjTransport()`).
  Minimal: an overload (or a `HostKeyPolicy` + path parameter) so the agent can build the engine with TOFU.
- `hive-agent` `AgentConfig`: `HIVEKEEPER_SSH_HOSTKEY` = `tofu` (default) | `strict` | `accept-all`, and
  `HIVEKEEPER_KNOWN_HOSTS` (default e.g. `./hivekeeper-known_hosts`, next to the vault). `AgentMain` wires them.
- `hive-cli` / `hive-server` keep building via `HiveCore.localEngine` and inherit the default (TOFU), so they
  stop being promiscuous too — but offer `accept-all` for a lab one-off.

### Recovery for a changed key

Slice 1: documented manual recovery (delete the host's line from the managed known_hosts file, then reconnect to
re-TOFU). A management action ("re-trust this AP") in the gateway/UI is a small follow-up, not slice 1.

## Module changes

| Module | Change |
|---|---|
| `hive-core` | Add `HostKeyPolicy.TOFU`. New `TofuKnownHostsVerifier` (sshj `HostKeyVerifier` over a managed known_hosts file: add-on-first-use, compare thereafter). `SshjTransport`: accept a known_hosts path; install the verifier for `TOFU`/`KNOWN_HOSTS`. `HiveCore.localEngine`: overload to pass the policy + path (default stays back-compatible). |
| `hive-agent` | `AgentConfig`: `HIVEKEEPER_SSH_HOSTKEY` + `HIVEKEEPER_KNOWN_HOSTS`. `AgentMain`: build the engine's transport with the configured policy + path (default TOFU). |
| `docs` | Flip README / `roadmap.md` / `agent-protocol.md` "Production security → SSH host-key verification (ACCEPT_ALL)" to done; document the env + the re-trust recovery. |

## Testing

- `TofuKnownHostsVerifier` unit test (temp known_hosts file): first `verify(...)` for a host **adds + accepts**;
  the same key **accepts**; a **different** key for the same host **rejects**; entries persist across reloads.
- A policy-selection test: `ACCEPT_ALL` accepts anything; `TOFU` enforces after first use.
- No live SSH server needed — the verifier is exercised directly.

## Risks / open questions

- **Legitimate key change** (factory reset / firmware) → mismatch → operator must clear the entry. Slice 1 =
  documented; a UI/CLI "re-trust" is a follow-up.
- Per-agent store (each agent has its own known_hosts) — fine for mode C.
- `host` vs `host:port` keying, and host represented by IP vs name (APs are reached by IP here → key by IP).
- Default policy choice: **TOFU** is the pragmatic default; `strict` (pre-seeded keys, no first-use add) is a
  later option for high-assurance sites.

## Size estimate

**Small / medium** — one verifier class + a transport/`HiveCore` wiring tweak + agent config + unit tests + a
recovery note. No new dependencies (sshj already present), no DB, no new crypto authority. Much lighter than
Plan A; good "high-value, low-risk" pick.
