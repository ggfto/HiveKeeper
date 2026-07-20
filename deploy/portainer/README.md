# `deploy/portainer/`

Supporting files for `docker-compose.portainer.yml` — the stack that runs HiveKeeper behind a Cloudflare
Tunnel with no published ports. The operator-facing runbook is [`docs/portainer-cloudflare.md`](../../docs/portainer-cloudflare.md).

## Why these Dockerfiles exist

A Portainer Git stack clones the repository somewhere the Docker daemon cannot bind-mount from, so
`./deploy/keycloak/bootstrap.sh:/bootstrap.sh:ro` mounts an **empty directory** rather than failing. The
stack then comes up half-configured, silently.

`build:` does work — the checkout is the build context. So every script that `docker-compose.prod.yml`
bind-mounts is `COPY`ed into a small image here instead. They are three-line `FROM` + `COPY` files and
build in seconds, layer-cached.

The alternative — inlining the scripts into the compose file as heredocs — was rejected. `bootstrap.sh` is
123 lines dense with `${VAR:?message}`, `$(...)` and quoted `-s` arguments, all of which would need `$`
doubling with no linter and no test to catch a mistake. Worse, it would fork the file: the same realm
configuration would exist twice, and the first person to fix a bug in one copy and not the other would
produce a realm that differs between deployment paths.

## Consequence

The scripts under `deploy/keycloak/` and `deploy/postgres/` are **shared with `docker-compose.prod.yml`**.
Editing one changes both stacks. That is the point — one source of truth — but it does mean a change here
needs testing on both paths.

## Contents

| File | |
| --- | --- |
| `postgres.Dockerfile` | Postgres with the restricted-app-role init script baked in. |
| `keycloak-init.Dockerfile` | The realm/client/GitHub-IdP bootstrapper. |
| `backup.Dockerfile` | The periodic `pg_dump` sidecar. |
| `pki-init.Dockerfile`, `gen-pki.sh` | Mints the private CA and the gateway's agent-facing certificate into a volume, once. Refuses to regenerate. |
| `cloudflared-init.Dockerfile`, `cloudflared/init.sh` | Creates the tunnel through the Cloudflare API, renders the ingress from the hostname variables, upserts the DNS records. |
| `agent-compose.yml`, `agent.env.example` | The on-prem agent and its `cloudflared access tcp` sidecar. Runs on the LAN machine, not the server. |
| `stack.env.example` | Template for Portainer's environment-variables panel. |
