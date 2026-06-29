# hive-agent

The HiveKeeper on-prem agent. It embeds the real engine (sshj transport + ServiceLoader drivers,
credentials resolved **locally**) and dials **out** to the gateway over a WebSocket — there are no
inbound ports on the LAN. It receives `Job`s, runs them, and streams `Event`s + the `Result` back.

It is `hive-core` + a thin connector: the same `AgentRuntime` proven by the in-memory loopback in
`hive-protocol`, here driven by a real `WebSocketFrameChannel` with automatic reconnect (exponential
backoff + jitter) that re-announces on every connect.

## Configuration (env)

| Variable | Default | Meaning |
| --- | --- | --- |
| `HIVEKEEPER_GATEWAY_URL` | `ws://127.0.0.1:8090/agent` | gateway WebSocket URL |
| `HIVEKEEPER_AGENT_ID` | hostname | stable agent identifier |
| `HIVEKEEPER_DEFAULT_USER` | `admin` | fallback device username (used when no `credRef` resolves) |
| `HIVEKEEPER_DEFAULT_PASSWORD` | _(empty)_ | fallback device password |
| `HIVEKEEPER_CREDENTIAL_VAULT` | _(unset)_ | path to a per-device credential vault (properties: `<credRef>.user` / `<credRef>.password`). When set, HiveKeeper can also **manage credentials from the UI** (the gateway seals the secret to this agent's key; the agent writes the vault). |
| `HIVEKEEPER_VAULT_KEY` | _(unset)_ | base64 AES-256 key that encrypts vault passwords **at rest**; without it the vault is written in plaintext (a logged warning) |
| `HIVEKEEPER_BACKUP_DIR` | `hivekeeper-backups` | local git backup directory |

> Resolution always happens on the agent — the cloud never sees device secrets, only an opaque `credRef`.
> With a vault configured, each device's `credRef` maps to its own local secret (encrypted at rest), and the
> credential can be set or rotated from the UI: the gateway seals it to the agent's public key (it holds no
> plaintext) and the agent unseals it with its mTLS private key before writing the vault. Without a vault, one
> default credential covers the fleet.

## Run / distribute

```sh
# dev
HIVEKEEPER_GATEWAY_URL=ws://127.0.0.1:8090/agent ./gradlew :hive-agent:run

# build a runnable distribution (start script + jars)
./gradlew :hive-agent:installDist        # -> hive-agent/build/install/hive-agent/bin/hive-agent

# container
docker build -f hive-agent/Dockerfile -t hivekeeper-agent .
docker run -e HIVEKEEPER_GATEWAY_URL=wss://gw/agent hivekeeper-agent
```

- **Windows service**: `packaging/windows/hive-agent.xml` (WinSW). See the comments in that file.
- **Linux service**: `packaging/linux/hive-agent.service` (systemd). See the comments in that file.

## Status

The gateway side (`hive-gateway`) is built, and the full chain is proven live: HTTPS/OIDC (operator) →
mTLS WebSocket (agent, cert identity) → SSH (agent → AP230), including submit-while-offline → reconnect →
redelivered → succeeded. Durable jobs, idempotent redelivery, and `Resume` are handled by the gateway's
job DB (the `postgres` profile); `WebSocketLoopbackTest` still proves the agent in isolation against a stub
gateway over a real socket. What remains is automated certificate enrollment — see the root README's
[Roadmap](../README.md#roadmap) and [`docs/agent-protocol.md`](../docs/agent-protocol.md).
