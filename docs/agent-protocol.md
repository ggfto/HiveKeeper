# HiveKeeper agent ⇄ gateway protocol

Status: **design + reference implementation** (`hive-protocol`). The WebSocket transport and the
`hive-agent` / `hive-gateway` deployables are not built yet; everything below the channel is.

## Why it looks like this

The on-prem agent lives on a private LAN behind NAT/firewall and holds the SSH reach to the APs. The
cloud cannot connect inward, so the **agent dials out** and the cloud only ever *responds* on that
connection. The wire payload is the **already-serializable in-process API** (`Command` / `Event` /
`Result`, serialized by `hive-wire`), so the agent literally does `engine.execute(decode(frame))`.

```
   Browser / API            CLOUD GATEWAY                         ON-PREM AGENT (hive-core + connector)
        │  HTTPS/SSE              │                                        │
        │ ───────────────▶  RemoteEngine.execute(cmd, sink)               │
        │                        │ ── Frame.Job(jobId, cmd) ───────────▶  │  (over outbound WSS:443)
        │                        │                                        │  engine.execute(cmd, sink)
        │   ◀── SSE events ──────│ ◀──── Frame.JobEvent(jobId, seq) ───── │   sink.emit(event)
        │                        │ ───── Frame.Ack(jobId, seq) ────────▶  │
        │   ◀── SSE result ──────│ ◀──── Frame.JobResult(jobId) ───────── │   (or Frame.JobFailed)
```

`RemoteEngine` implements the same `Engine` interface as `LocalEngine`; the caller cannot tell whether
work runs in-process or on an agent 1000 km away. (`LoopbackProtocolTest` proves this with an in-memory
channel — no socket.)

## Frames (`io.hivekeeper.protocol.Frame`)

| Frame | Direction | Purpose |
| --- | --- | --- |
| `Hello(agentId, protocolVersion)` | agent → gw | identify + version handshake on connect |
| `Resume(agentId, lastJobId, lastSeq)` | agent → gw | after reconnect, request redelivery |
| `Job(jobId, idempotencyKey, deadlineEpochMs, command)` | gw → agent | a unit of work |
| `JobEvent(jobId, seq, event)` | agent → gw | streamed progress; `seq` monotonic per job |
| `JobResult(jobId, result)` | agent → gw | terminal success |
| `JobFailed(jobId, error, detail)` | agent → gw | terminal failure |
| `Ack(jobId, ackedSeq)` | gw → agent | confirms receipt up to `seq` |
| `Heartbeat(epochMillis)` | both | liveness |

## Transport & resilience (target for `hive-agent` / `hive-gateway`)

- **One persistent outbound WebSocket over TLS:443**, multiplexed by `jobId`. No inbound ports, proxy-friendly.
- **Heartbeat** ~20–30s each way; miss N in a row ⇒ reconnect.
- **Reconnect** with exponential backoff + jitter. On reconnect the agent sends `Resume`; the gateway
  redelivers un-acked work from its job DB.
- **Idempotency**: `Job.idempotencyKey` lets the agent dedupe a redelivered job; `JobEvent.seq` + `Ack`
  give at-least-once streaming with gap/dup detection.
- **Offline buffering** lives in the control-plane DB (per-agent, TTL'd, idempotency-keyed) — **not** a
  broker.

## Security

- Device credentials **stay on-prem**: the agent resolves them via a local `CredentialProvider`; the
  cloud stores only device refs + intent + metadata.
- Enrollment: one-time token (scoped `tenantId`/`siteId`) → agent generates a keypair **locally** →
  mTLS client cert (CA-pinned, short-lived, auto-renewed). `tenantId` is derived server-side from the
  agent record, never trusted from the client.

## Not yet implemented

`Frame` versioning is a single `Protocol.VERSION` string today. Still to build: the WebSocket
`FrameChannel`, the gateway job DB + redelivery, mTLS enrollment, and the `hive-agent` (service +
container) / `hive-gateway` (Spring) deployables that wrap `AgentRuntime` / `RemoteEngine`.
