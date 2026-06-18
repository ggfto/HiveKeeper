---
title: Deployment
description: Run the HiveKeeper stack with Docker Compose, pull the container images, and how releases are versioned.
---

The dev scripts in [Getting started](/getting-started/) are the quickest way to hack on HiveKeeper. To run
it as a deployed stack — the gateway plus an on-prem agent, optionally with Postgres and OIDC — use the
committed Docker Compose files. (Everything here works the same with `podman compose`.)

## Compose stack

The repo root ships a layered set of compose files:

| File | Adds |
| --- | --- |
| `docker-compose.yml` | The base: the **gateway** (in-memory, no DB) and one **agent**. |
| `docker-compose.postgres.yml` | **Postgres** + switches the gateway to the `postgres` profile (durable jobs, org/users/fleet, audit log, RLS). |
| `docker-compose.keycloak.yml` | A dev **Keycloak** (imports the `hivekeeper` realm) + the `oidc` profile for per-user login. |

### Bring it up

```sh
# Simplest: gateway (in-memory) + agent, built from source on a fresh clone.
docker compose up -d --build

# With persistence (Postgres):
docker compose -f docker-compose.yml -f docker-compose.postgres.yml up -d --build

# With persistence + OIDC/SSO (sign in at the console with owner/owner):
docker compose -f docker-compose.yml -f docker-compose.postgres.yml -f docker-compose.keycloak.yml up -d --build
```

The gateway listens on `:8090`. The agent dials **out** to it over WebSocket (no inbound ports) and resolves
SSH credentials locally — the cloud never sees them. Configure via env (copy `.env.example` to `.env`); the
key ones are `HIVEKEEPER_DEFAULT_USER` / `HIVEKEEPER_DEFAULT_PASSWORD` (the agent's fallback SSH login) and,
for the Postgres profile, `HIVEKEEPER_CRYPTO_KEY` (job-secret encryption — set a real key in production:
`openssl rand -base64 32`).

:::note
The web console (`hive-web`) is served separately by design — it is not bundled into the gateway. Run it
with `pnpm --dir hive-web dev` (it proxies `/gw` to the gateway on `:8090`), or build the static `dist/` and
serve it from any static host / CDN.
:::

:::caution
Keycloak-in-Docker issuer gotcha: tokens are minted for the **browser-facing** URL (`http://localhost:8081`),
so the gateway validates the `iss` claim against that while fetching JWKs over the container network. If you
reach the console from another host or port, set `HIVEKEEPER_OIDC_ISSUER` to match where users log in.
:::

## Container images

Released images are published to the GitHub Container Registry:

- `ghcr.io/ggfto/hivekeeper-gateway`
- `ghcr.io/ggfto/hivekeeper-agent`
- `ghcr.io/ggfto/hivekeeper-server`

Each is tagged with the release version (e.g. `0.2.0`) and `latest`. To run the published images instead of
building from source, set the tag and pull:

```sh
HIVEKEEPER_TAG=latest docker compose pull && docker compose up -d
```

## Versioning & releases

Versioning is automated with [semantic-release](https://semantic-release.gitbook.io/) driven by
[Conventional Commits](https://www.conventionalcommits.org/). On every push to `main`, the `Release`
workflow inspects the commits since the last release and, if any warrant one (`feat` → minor, `fix` →
patch, `feat!`/`BREAKING CHANGE` → major):

1. bumps `version` in `gradle.properties` (the single source of truth for all JVM modules and the images),
2. updates `CHANGELOG.md`, commits and tags it (`vX.Y.Z`),
3. builds and pushes the gateway/agent/server images to GHCR (tagged with the version and `latest`),
4. creates the GitHub Release.

No release-worthy commits since the last tag → the workflow does nothing.

:::note
**One-time setup:** so the first automated release does not jump to `1.0.0`, create a baseline tag on the
current commit: `git tag v0.1.0 && git push origin v0.1.0`. The release job needs the repository's
`GITHUB_TOKEN` to have `contents: write` and `packages: write` (already set in the workflow).
:::
