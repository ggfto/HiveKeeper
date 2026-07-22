---
title: Portainer + Cloudflare Tunnel
description: Run HiveKeeper as a Portainer Git stack with no published ports — the console and Keycloak behind a Cloudflare Tunnel, the agents over a TCP tunnel that keeps mutual TLS intact.
---

This is the runbook for a deployment where the server **publishes no ports at all**. Cloudflare Tunnel is
the only way in: `cloudflared` dials out, so the host needs no public address, no inbound firewall rule and
no certificate of its own.

It is an alternative to [Running in production](/production/), not a replacement. That page describes the
same stack behind Caddy on a machine with ports 80, 443 and 9443 open. Pick one:

| | [Production](/production/) | This page |
| --- | --- | --- |
| Ingress | Caddy, on the host | Cloudflare Tunnel |
| Public ports | 80, 443, 9443 | **none** |
| TLS certificate | Let's Encrypt, via Caddy | Cloudflare's, at the edge |
| Deployed by | `docker compose` on the host | Portainer Git stack |

## The shape of it

Two machines, and the split is the whole point.

**The server** runs the control plane: the console, the gateway, Keycloak, Postgres, cloudflared. It never
faces the internet directly.

**The agent** runs *inside the network your access points are on*. It reaches the APs over SSH on the LAN,
resolves their credentials **locally**, and dials **out**. Your APs never need to be reachable from the
internet, that machine never needs an inbound port, and the server never holds a usable AP password: it
only ever sends a reference.

Two hostnames, both proxied CNAMEs to the tunnel, both created automatically:

| Hostname | Carries | Ingress type |
| --- | --- | --- |
| `hive.example.org` | The console, and Keycloak under `/auth` | `http` |
| `agents.example.org` | The agents | **`tcp`** |

That last row is not an oversight. An agent authenticates with a TLS **client certificate**, which the
gateway reads from the TLS connection itself — never from a header, because a header can be forged by
anything that can reach the port. An `http` ingress terminates TLS at Cloudflare's edge and would swallow
that certificate, so every agent handshake and every certificate renewal would fail. Quietly, and
completely, until you noticed the fleet had gone offline. A `tcp` ingress is a raw byte pipe: the TLS
session runs end to end from the agent to the gateway, certificate intact.

## 1. Cloudflare

### The API token

Create one at **My Profile → API Tokens** with two permissions:

- **Account → Cloudflare Tunnel → Edit**
- **Zone → DNS → Edit**, on the zone you are deploying into

The stack creates its own tunnel through the API on first boot and upserts a CNAME per hostname, so there
is no `cloudflared tunnel login`, no `cert.pem`, and nothing to place on the host by hand.

### The Access application for the agents

:::caution[Do this before deploying — the agent cannot connect without it.]
`cloudflared access tcp` is a Zero Trust client, not a plain socket. It authenticates to Cloudflare's edge
before the edge will broker the stream to your tunnel. With no Access application covering the agent
hostname, it tries to open a **browser** for interactive login — which never completes on a headless agent,
leaving the uplink permanently down with a confusing error.
:::

In **Zero Trust → Access → Applications**, add a **Self-hosted** application for `agents.example.org`, then
give it a policy with **Action: Service Auth** and a **Service Token**. Keep the token's Client ID and
Client Secret; the agent needs both.

This is a second, independent credential in front of mutual TLS, not a replacement for it.

## 2. Deploy the stack

In Portainer: **Stacks → Add stack → Repository**, pointing at this repository with the compose path
`docker-compose.portainer.yml`. Paste the variables from `deploy/portainer/stack.env.example` into the
**Environment variables** panel and fill them in.

Generate secrets with `openssl rand -base64 32` for keys and `openssl rand -hex 24` for passwords.

| Variable | |
| --- | --- |
| `HIVEKEEPER_TAG` | Pin the release. Never `latest`: an unattended restart would silently upgrade you. |
| `HIVEKEEPER_DOMAIN` | The console, e.g. `hive.example.org`. |
| `HIVEKEEPER_AGENT_DOMAIN` | The agents, e.g. `agents.example.org`. **Goes into the gateway certificate's SAN — choose it once.** |
| `CF_API_TOKEN`, `CF_ACCOUNT_ID`, `CF_ZONE_ID` | From step 1. |
| `TUNNEL_NAME` | One tunnel per stack; unique within the account. |
| `HIVEKEEPER_CRYPTO_KEY` | Encrypts secrets at rest in the database. |
| `HIVEKEEPER_DB_PASSWORD`, `HIVEKEEPER_DB_ADMIN_PASSWORD` | |
| `HIVEKEEPER_PKI_STORE_PASSWORD` | Opens all three PKI keystores. |
| `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_DB_PASSWORD` | |
| `HIVEKEEPER_GITHUB_CLIENT_ID`, `HIVEKEEPER_GITHUB_CLIENT_SECRET` | Optional; step 5. |

:::danger[Back up `HIVEKEEPER_CRYPTO_KEY` and `HIVEKEEPER_PKI_STORE_PASSWORD` now, somewhere other than Portainer.]
Losing the crypto key does not lose your data — it makes your data permanently unreadable. The ciphertext
is still sitting in the database; you simply can never open it again. Losing the PKI password has the same
effect on the keystores, which means re-enrolling every agent.

A Portainer environment panel *feels* more editable than a `chmod 600` file. It is not.
:::

Some settings are deliberately **not** variables, because they are switches that turn a hardened
deployment back into a development one: `HIVEKEEPER_TENANTKEY_ENABLED=false`,
`HIVEKEEPER_CRYPTO_ALLOW_INSECURE_DEV_KEY=false`, and the absence of `HIVEKEEPER_SOLO` — which turns every
unauthenticated request into an owner of the organization. Do not add it.

### What comes up

`pki-init` mints the private CA and the gateway's server certificate into a volume, then exits.
`cloudflared-init` creates the tunnel, renders the ingress from your two hostnames, and upserts the DNS
records, then exits. Both run on every deploy and both are idempotent.

Check their logs: `cloudflared-init` should list a `+` or `~` per hostname, and `cloudflared` should report
`Registered tunnel connection`.

:::note[`pki-init` refuses to regenerate an existing CA.]
Minting a new CA would orphan every agent already enrolled against the old one — they would all fail their
handshake at once, and the only fix is re-enrolling the whole fleet by hand. If the PKI is already in the
volume, `pki-init` says so and exits successfully without touching it.
:::

## 3. Security headers

Caddy set `Strict-Transport-Security`, `X-Content-Type-Options` and `Referrer-Policy` for everything it
served. With Caddy gone, the console's own nginx still sets the latter two for the SPA — but **not** for
`/auth/*`, and **HSTS is gone entirely**. Nothing fails; you simply lose the header.

Replace it at the edge: enable **HSTS** under **SSL/TLS → Edge Certificates**, and add a zone-level
**Response Header Transform Rule** for the rest. This is a required step, not an optional one.

## 4. First run

Open `https://hive.example.org` and complete the first-run setup with the token from the gateway's log.

Do this **immediately**. The setup endpoints are public until first-run completes — that is what lets you
claim the deployment, and it is equally what lets anybody else claim it.

## 5. Sign in with GitHub

Create a GitHub OAuth App with the homepage `https://hive.example.org` and this **exact** Authorization
callback URL:

```
https://hive.example.org/auth/realms/hivekeeper/broker/github/endpoint
```

Put the Client ID and Secret into the two environment variables and redeploy. The realm bootstrapper is
idempotent, so it re-runs and adds the identity provider.

GitHub is OAuth2, not OpenID Connect: it issues an opaque token and publishes no JWKS, so the gateway could
never validate one directly. Keycloak brokers instead — it authenticates the user against GitHub and mints
one of *its own* tokens, which the gateway already knows how to validate. Nothing in the gateway changes.

:::note[Signing in with GitHub does not grant access.]
A brokered identity only proves *who* someone is. The gateway refuses any token for a user who is not a
member of the organization — otherwise every GitHub account on earth would have an account on your
deployment. Invite each user in the console after they have signed in once.
:::

## 6. The agent

On a machine inside your access points' network — **not** the server:

1. Copy `deploy/portainer/agent-compose.yml` and `deploy/portainer/agent.env.example` to it, and rename the
   latter to `.env`.
2. Get the CA certificate: in Portainer, open the `pki-init` container's log and copy the block between
   `BEGIN ca.pem` and `END ca.pem` into a file named `ca.pem` next to the compose file. It is the CA
   certificate — public, not a secret. The log prints it on every deploy, so it stays available for the
   next agent.
3. In the console, **Fleet → Agents → Enroll**, and put the one-time token into `.env` along with the
   Access service token from step 1.
4. `docker compose -f agent-compose.yml up -d`

The agent generates a keypair, posts a CSR, receives its certificate, and only then opens its uplink. The
token is spent; from then on the agent renews its own certificate over mutual TLS.

:::danger[Do not "simplify" `HIVEKEEPER_ENROLLMENT_URL` to the public console URL.]
It would work. Enrollment is authenticated by the one-time token, so it survives a TLS-terminating proxy
perfectly — and you would see an agent connect and run jobs normally.

Then, about two months later, the first certificate **renewal** fails with `401 no_client_cert` and the
agent drops off the fleet. Renewal is authenticated by the agent's current client certificate, so it must
reach the gateway unterminated, through the same TCP tunnel. The agent has only one enrollment URL for both
flows, so it has to be the `:9443` one.
:::

Success looks like: the container reports **healthy** (the healthcheck watches the uplink, not the process,
so healthy means *connected*), and the agent shows online in the console.

## 7. Verify

```sh
# The console, and Keycloak's issuer — it must match exactly, with no port and no doubled /auth.
curl -sI https://hive.example.org/
curl -s https://hive.example.org/auth/realms/hivekeeper/.well-known/openid-configuration | jq .issuer

# The console's proxy to the gateway.
curl -s https://hive.example.org/gw/api/mode

# Ingress rule ordering: this must reach the SPA, NOT Keycloak. If it 404s from Keycloak, the /auth
# path rule lost its anchor and is swallowing every route that merely starts with "auth".
curl -sI https://hive.example.org/authentic
```

Then run a real device job — an Inventory or a Backup — from the console. That is the only check that
exercises the whole path: browser → tunnel → gateway → TCP tunnel → agent → SSH to the access point.

### Force a certificate renewal on day one

The natural path takes two months. Force it instead: from the agent container, POST a CSR to
`https://agents.example.org:9443/api/enrollments/certificate/renew` using the agent's current certificate
and key. A `200` with a PEM bundle proves mutual TLS survives the tunnel. A `401 no_client_cert` means the
tunnel is stripping the client certificate, and the agent link needs rethinking — **find that out now, not
in sixty days.**

## Upgrades and backups

Upgrade by bumping `HIVEKEEPER_TAG` and redeploying. Pin the agent to the same version as the gateway — or let
the agent follow releases on its own with the opt-in auto-updater (`--profile autoupdate`, see
[Running in production](/production/) → *Letting the agent update itself*; on Podman it needs the
Docker-compatible socket).

For redundancy, enrol a **second agent** that can reach the same APs (add it to a device from the console, or
adopt the AP from it) and the two run active/standby automatically — it need not share the device's site;
and set the organization's **backup destination** (a git repository) in the console so the fleet's config
history lands off the agents. Both are covered in [Running in production](/production/).

The `backup` service takes a periodic `pg_dump` of **both** databases into the `pg-backups` volume — both,
because either alone is useless: restore the gateway's without Keycloak's and every role grant points at a
user id that no longer exists, so nobody can sign in, you included.

Four things that backup does **not** cover, and that you must back up yourself:

- `HIVEKEEPER_CRYPTO_KEY` — without it the dump is unreadable.
- `HIVEKEEPER_PKI_STORE_PASSWORD` and the `pki` volume — the CA that signs every agent certificate.
- The agent's `agent-data` volume — its identity, its credential vault, its pinned SSH host keys, and the
  git history of every device config, which is your rollback path.
