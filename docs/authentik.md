---
title: Authentik as the identity provider
description: Run HiveKeeper's OIDC mode against Authentik instead of Keycloak, and what differs.
---

HiveKeeper's OIDC mode works against [Authentik](https://goauthentik.io/) as well as Keycloak. The gateway is
provider-agnostic: it validates JWTs from whatever issuer you point it at, and talks to the IdP's admin API
only to create accounts. Which client it uses is one property.

Everything on [Authentication](/authentication/) still applies — this page is only the Authentik-specific
part. If you have no IdP preference, use Keycloak: it is the default and has one fewer moving part.

## Selecting the provider

```properties
hivekeeper.idp=authentik      # or `keycloak`, the default
```

Set it through the `oidc-authentik` profile, which carries it along with the Authentik URLs:

```
SPRING_PROFILES_ACTIVE=postgres,oidc,oidc-authentik
```

:::note[The `oidc` profile stays on]
`oidc-authentik` is layered **on top of** `oidc`, not instead of it. Every OIDC bean — the resource-server
config, first-run setup, member management — requires `oidc`; the extra profile only overrides the
IdP-specific settings. Dropping `oidc` leaves the gateway with no authentication at all.
:::

## Running the dev stack

```bash
cp .env.authentik.example .env      # then fill in the two required secrets
docker compose -f docker-compose.yml -f docker-compose.postgres.yml -f docker-compose.authentik.yml up -d --build
```

`.env` needs two values with no safe default — generate both with `openssl rand -hex 32`:

| Variable | What it is |
| --- | --- |
| `HIVEKEEPER_AUTHENTIK_API_TOKEN` | Seeded as the `akadmin` API token on first boot, then used by both the bootstrap job and the gateway. One token, both jobs. |
| `AUTHENTIK_SECRET_KEY` | Signs Authentik's own sessions. |

The `authentik-init` service then runs `deploy/authentik/bootstrap.sh`, which creates the `hivekeeper`
application, its OAuth2 provider, and the brand's recovery flow. It is idempotent, so it re-runs on every
`up` and reconciles drift. The same script works standalone against an Authentik you already operate:

```bash
AUTHENTIK_URL=https://sso.example.org \
HIVEKEEPER_AUTHENTIK_API_TOKEN=... \
HIVEKEEPER_CONSOLE_URL=https://hivekeeper.example.org \
  ./deploy/authentik/bootstrap.sh
```

The API token needs permission to manage providers, applications and brands.

## Two settings that are load-bearing

If you build the provider by hand instead, these two are not cosmetic. Both fail in the same nasty way — the
account is created and *then* cannot sign in.

### Subject mode must be "Based on the User's ID"

When the gateway provisions someone it stores the Authentik `pk` as their OIDC subject, and later matches
them by the `sub` claim in their token. Authentik's **default** subject mode is a salted hash of the id, which
never equals the `pk` — so the first admin is created, and their very first sign-in resolves to nobody.

Set the provider's `sub_mode` to `user_id` (in the UI: *Subject mode → Based on the User's ID*).

### The brand needs a recovery flow

Adding a teammate mints a one-time recovery link (see below). Authentik refuses that call unless a recovery
flow is set as the **active brand's default** — *"The current brand must have a recovery flow configured to
use a recovery link"* — and adding a teammate then fails with a message saying so. The bootstrap script binds
the built-in `default-recovery-flow` when the brand has none; if your Authentik has no recovery flow at all,
import one first (*Flows → Import*, "Recovery with email verification") and re-run the script.

## What differs from Keycloak

### Adding a teammate hands you a link, not a password

Keycloak can mark a password temporary and pin an `UPDATE_PASSWORD` action, so an admin's throwaway password
stops working the moment the teammate signs in. **Authentik has no equivalent flag.** Setting a password and
calling it temporary would mean the admin keeps a working credential for someone else's account forever.

So under Authentik the gateway creates the account with *no usable password* and asks Authentik for a
one-time recovery link. The console shows it after the add:

> Send this link to bob — they set their own password with it; you never see it.

It is shown **once** and cannot be re-issued. If you lose it, remove the member and add them again. The
password field in the add form is ignored under Authentik.

### Everything else is the same

Admitting an existing account (the no-password path, for anyone who signs in through a federated provider),
roles, org scoping and JWT validation are unchanged — those live in HiveKeeper's own database, not the IdP.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| Gateway exits at startup: `No qualifying bean of type 'IdpAdminClient'` | `hivekeeper.idp` is set to something that is neither `keycloak` nor `authentik` (a typo fails loudly rather than silently picking the wrong IdP). |
| First admin is created but cannot sign in | Provider subject mode is not `user_id`. |
| `Authentik created 'x' but issued no recovery link ... set a recovery flow` | No recovery flow set as the brand's default. The named account was left behind — delete it in Authentik before retrying. |
| Token rejected: `iss` mismatch | `HIVEKEEPER_OIDC_ISSUER` must be the URL the **browser** logs in at, not the container name. The gateway reaches the API over the container network separately. |
| Token rejected, JWKS empty | The provider has no asymmetric signing key, so Authentik signed with HS256. Give it a certificate keypair. |
