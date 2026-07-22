---
title: Running in production
description: Deploy HiveKeeper for real — TLS, generated secrets, per-user login (including GitHub), an on-prem agent, backups and upgrades.
---

This is the runbook for a deployment you intend to keep. It is separate from [Deployment](/deployment/),
which covers the development compose stack — that stack is deliberately zero-config, and its defaults (a
public encryption key, the password `app`, a Keycloak whose users' passwords are their usernames) are exactly
what you must not run in production.

The production stack refuses those defaults rather than inheriting them: `docker-compose.prod.yml` is
self-contained, and every secret is a required variable. If one is missing it will not start.

:::note[No public ports, or deploying through Portainer?]
This page assumes a machine with a domain pointed at it and ports 80, 443 and 9443 open. If you would
rather publish nothing at all, [Portainer + Cloudflare Tunnel](/portainer-cloudflare/) covers the same
stack with `cloudflared` as the only ingress.
:::

## The shape of it

Two machines, and the split is the whole point.

**The server** runs the control plane: the console, the gateway, Keycloak, Postgres, Caddy. It faces the
internet.

**The agent** runs *inside the network your access points are on* — your office, your rack, the closet with
the switch. It reaches the APs over SSH on the LAN, resolves their credentials **locally**, and dials **out**
to the gateway. So your APs never need to be reachable from the internet, that machine never needs an inbound
port, and the server never holds a usable AP password: it only ever sends a reference.

Three ports on the server:

| Port | For | Behind the proxy? |
| --- | --- | --- |
| 80, 443 | The console and Keycloak | **Yes** — Caddy terminates TLS and gets the certificate. |
| 9443 | **The agents** | **No.** |

That last row is not an oversight. An agent authenticates with a TLS **client certificate**, which the gateway
reads from the TLS connection itself — never from a header, because a header can be forged by anything that
can reach the port. A TLS-terminating proxy in front of 9443 would swallow the certificate, and every agent
handshake and every certificate renewal would fail. Quietly, and completely, until you noticed the fleet had
gone offline. So the gateway listens twice: plain HTTP internally for the console's proxy, and TLS with client
authentication on 9443, published straight through.

## 1. Prepare the server

You need a machine with a container engine, a domain that resolves to it, and ports 80, 443 and 9443 open.
80 and 443 must be reachable from the internet or Let's Encrypt cannot issue your certificate.

```sh
git clone https://github.com/ggfto/HiveKeeper.git && cd HiveKeeper
scripts/init-secrets.sh hivekeeper.example.org
```

That generates, once:

- **`.env.prod`** — every secret, `chmod 600`.
- **`secrets/pki/`** — the private CA that signs agent certificates, the gateway's server certificate for port
  9443, and `ca.pem` (the CA certificate, which you hand to each agent — it is public, not a secret).

It **refuses to overwrite**. Re-running it would mint a new CA — orphaning every agent already enrolled — and
a new `HIVEKEEPER_CRYPTO_KEY`, which would make every secret already in the database permanently unreadable.
The ciphertext would still be sitting there; you simply could never open it again.

:::danger[Back up `.env.prod` and `secrets/pki/` now, somewhere other than this machine.]
`HIVEKEEPER_CRYPTO_KEY` decrypts every secret in the database. `ca.p12` is the fleet's trust. A database
backup without the key is a pile of ciphertext, and no CA means re-enrolling every agent by hand.
:::

Then set `HIVEKEEPER_ACME_EMAIL` in `.env.prod` — Let's Encrypt sends expiry warnings there.

## 2. Bring the stack up

```sh
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d
```

Caddy gets a certificate for your domain by itself. `keycloak-init` configures the realm and the console's
client, then exits — that is not a crash.

Now create the first organization and its owner. The gateway prints a one-time setup token on first boot, so
completing setup requires access to the server:

```sh
docker compose --env-file .env.prod -f docker-compose.prod.yml logs gateway | grep -i "setup token"
```

Open `https://hivekeeper.example.org`, paste the token, and choose your admin username and password. The
endpoint locks itself the moment the first organization exists.

## 3. Sign in with GitHub (optional)

GitHub is OAuth2, not OpenID Connect: it hands out an opaque token, no `id_token`, and publishes no JWKS for
user login. The gateway validates JWTs by signature, issuer and audience, so it can never accept a GitHub token
directly. **Keycloak brokers it** — it authenticates the user against GitHub and mints one of its own tokens,
which the gateway already knows how to validate.

Create a GitHub OAuth App with exactly this **Authorization callback URL**:

```
https://hivekeeper.example.org/auth/realms/hivekeeper/broker/github/endpoint
```

Put its client id and secret in `.env.prod` (`HIVEKEEPER_GITHUB_CLIENT_ID` / `_SECRET`) and bring the stack up
again. `keycloak-init` is idempotent; it will add the provider. The **Sign in with GitHub** button then appears
on the login page.

One thing trips everyone up. A GitHub user has no password and **no account at all until their first
sign-in creates one** — so you cannot add them to your organization in advance. The flow is:

1. They sign in with GitHub. They are told they belong to no organization. (This is expected.)
2. You go to **Members → Add member → Existing account** and add them by username or e-mail.
3. They reload, and they are in.

## 4. Run the agent, on-prem

On a machine **on the same network as your access points** — not the server:

```sh
# In the console: Fleet -> Agents -> Enroll. Copy the one-time token.
cp hive-agent/agent.conf.example agent.conf
# Copy secrets/pki/ca.pem from the server, next to agent.conf.
docker compose -f docker-compose.agent.yml up -d
```

The essential lines of `agent.conf`:

```properties
gateway.url      = wss://hivekeeper.example.org:9443/agent
agent.id         = site-a-agent
enrollment.token = <the one-time token>
enrollment.url   = https://hivekeeper.example.org:9443
enrollment.cacert = /etc/hivekeeper/ca.pem

# Encrypts the credential vault at rest. openssl rand -base64 32
vault.key         = ...
credential.vault  = /data/vault.properties
```

On first start the agent generates a keypair, exchanges the token for a signed certificate, and connects. The
token is then spent. From there it renews its own certificate before expiry, over its current one — no token,
no downtime, nothing for you to remember.

Environment variables override `agent.conf`, so set one or the other, not both.

:::note[Back up the agent's `/data` volume.]
It holds the agent's identity (its keystore), the credential vault, the pinned SSH host keys, and the git
history of every device configuration — which is your rollback path. It is not on the server, so the server's
backups do not cover it.
:::

### A second agent for redundancy (active/standby)

Enrol a second agent that can reach the same APs — typically another node on the same LAN, but it needn't share
the device's site. Point both at an AP (adopt it from either, or add the agent on the device page) and they can
both drive it: one becomes the serving agent and does all unattended work; the other stands by and takes over
automatically if it drops. The serving agent is the connected reachable agent whose id sorts first, so choose it
by naming — `site-a-01` (serving), `site-a-02` (standby). Nothing else to configure: the gateway elects and
fails over, and the agents never talk to each other. Adopting the same AP from either agent converges to one
device, reachable by both.

### Where the fleet's config history goes

Set an organization-wide git repository in the console under **Agents → Backup destination**, and every agent
pushes its config history there — so the history survives the machine that captured it. The token is sealed to
each agent and stored encrypted; scope it to that repository only. A push that fails does not fail the backup
(the local commit is the rollback path); pending commits go out with the next capture.

## Backups

The `backup` service takes a `pg_dump` of **both** databases every 24h (configurable), keeps 14 days, and
writes to the `pg-backups` volume.

Both, because either alone is useless: restore the gateway's database without Keycloak's, and every role grant
points at a user id that no longer exists — nobody can sign in, you included.

What backups do **not** cover, and you must:

| | Why it matters |
| --- | --- |
| `.env.prod` | `HIVEKEEPER_CRYPTO_KEY` decrypts the secrets *inside* the dumps. Without it, a restore gives you ciphertext. |
| `secrets/pki/` | The CA that signs agent certificates. Without it, the whole fleet re-enrolls. |
| The agent's `/data` volume | Credential vault, SSH host keys, config-backup history. On the agent's machine. |

To restore:

```sh
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d postgres keycloak-db
zcat backups/hivekeeper-<stamp>.sql.gz | docker compose ... exec -T postgres psql -U postgres -d hivekeeper
zcat backups/keycloak-<stamp>.sql.gz   | docker compose ... exec -T keycloak-db psql -U keycloak -d keycloak
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d
```

Restore into empty databases, with the **same** `.env.prod` you backed up.

## Upgrades

`HIVEKEEPER_TAG` is pinned in `.env.prod`, and must be. With `:latest`, an unattended container restart would
silently upgrade your control plane at three in the morning.

```sh
# Read the release notes first: https://github.com/ggfto/HiveKeeper/releases
sed -i 's/^HIVEKEEPER_TAG=.*/HIVEKEEPER_TAG=0.7.0/' .env.prod
docker compose --env-file .env.prod -f docker-compose.prod.yml pull
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d
```

Flyway migrates the schema on start. Take a backup first — migrations are not reversible.

Upgrade the agents to the same version afterwards. The protocol is versioned and an older agent keeps working,
but do not let them drift far.

### Letting the agent update itself

An agent can follow new releases on its own. It is **opt-in**, and it fires only when the agent's image tag
*moves* — so a pinned `HIVEKEEPER_TAG` never auto-updates, and pointing it at a moving tag (`latest`, or a
channel you promote in step with the gateway) makes the agent track it:

```sh
docker compose -f docker-compose.agent.yml --profile autoupdate up -d
```

That starts a small updater ([Watchtower](https://containrrr.dev/watchtower/)) scoped by a label to **only**
the agent — it touches nothing else on the machine. When a new image appears it stops the agent, and the agent
**drains** first: it lets the job it is running finish and report before it exits, so the gateway marks that
job done rather than redelivering it to the replacement container and running it twice. `stop_grace_period` and
`HIVEKEEPER_SHUTDOWN_DRAIN_SECONDS` size that window.

:::note[The drain is not only for auto-update.]
Any restart — a reboot, a redeploy, a manual `docker compose up` — now lets an in-flight job finish first. The
auto-updater is just the case that made it worth doing.
:::

On Podman, Watchtower needs the Docker-compatible socket: `systemctl --user enable --now podman.socket`, then
set `CONTAINER_SOCK` to it (e.g. `/run/user/1000/podman/podman.sock`).

Auto-update trades a small drift window for not having to touch the machine: the updater polls on an interval,
so between a release and the next poll the agent may be a version behind the gateway. That is usually fine — the
wire protocol only changes on a breaking envelope change, not every release — but it is why the recommendation
remains to promote a moving tag deliberately rather than chase `latest` blindly on the control plane.

## What is deliberately not exposed

The gateway's HTTP port, its metrics, and both databases stay on the internal network. Health and metrics live
on their own port (9091, `/actuator/health` and `/actuator/prometheus`), which no compose file publishes — so a
monitoring stack on the same network can scrape them and the internet cannot see them.

`HIVEKEEPER_SOLO` appears nowhere in the production stack, and must never be added: it turns every
unauthenticated request into the owner. It exists for a single-user gateway on a trusted machine, and nothing
else.
