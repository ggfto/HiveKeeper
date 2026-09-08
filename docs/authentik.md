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

The API token needs permission to manage providers, applications, flows and brands.

The script adapts to the Authentik version it finds: `redirect_uris` became a list of objects in 2024.10 and
was a newline-separated string before, so it tries the current shape and falls back. A line like
`>> this Authentik does not take redirect_uris as a list` is that probe succeeding, not an error.

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
use a recovery link"* — and adding a teammate then fails with a message saying so.

Authentik ships **no** recovery flow: out of the box the only flow that touches passwords is
`default-password-change`, whose designation is `stage_configuration`, not `recovery`. So the bootstrap
script creates a minimal one (`hivekeeper-recovery`) bound to the same prompt + user-write stages that flow
already uses, and sets it as the brand default — but only when the brand has none, so your own choice is
never overwritten.

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

## Migrating an existing deployment from Keycloak

Your organizations, sites, groups, role grants and audit history all survive: `membership` and `role_grant`
reference `app_user.user_id`, an internal id that never changes. Only the *login key* — the
`(oidc_issuer, oidc_subject)` pair on `app_user` — belongs to the old IdP, and re-keying it is the whole
migration.

:::caution[Read this before you start]
Until the re-key lands, an account that signs in through Authentik resolves to nobody and the console says it
belongs to no organization. That is the failure mode you should expect, and it is reversible — `/api/me` looks
users up, it never creates them, so a premature sign-in leaves no duplicate row behind. What is *not*
reversible is losing the old values, so step 1 is not optional.
:::

### 1. Back up, and write down what you are changing

```sh
docker compose --env-file .env.prod -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U postgres hivekeeper | gzip > hivekeeper-pre-authentik.sql.gz

# The current login keys — your rollback values. Keep this output.
docker compose --env-file .env.prod -f docker-compose.prod.yml exec -T postgres \
  psql -U postgres -d hivekeeper -c \
  'select user_id, oidc_issuer, oidc_subject, email, name from app_user order by created_at;'
```

Keep the Keycloak database and its volume until you have signed in through Authentik. Rolling back is only
cheap while both halves still exist.

### 2. Configure Authentik

Run [the bootstrap script](#running-the-dev-stack) against your instance. It creates the application, the
provider with `sub_mode: user_id`, and the brand's recovery flow.

### 3. Make sure each person exists in Authentik, and get their `pk`

```sh
curl -sf -H "Authorization: Bearer $HIVEKEEPER_AUTHENTIK_API_TOKEN" \
  "$AUTHENTIK_URL/api/v3/core/users/?email=olivia@example.org" \
  | jq -r '.results[] | "\(.pk)\t\(.username)\t\(.email)"'
```

That `pk` is what `sub_mode: user_id` puts in the `sub` claim, so it is exactly what `app_user.oidc_subject`
must become. If someone has no account yet, create one — the console's **Add member** flow does this for a new
teammate, but for a person who is *already* a member you want the account only, so create it in Authentik
directly and hand them a recovery link.

### 4. Re-key `app_user`

One statement per person, in a transaction, keyed on the `user_id` you recorded in step 1:

```sql
begin;

update app_user
   set oidc_issuer  = 'https://sso.example.org/application/o/hivekeeper/',
       oidc_subject = '42'
 where user_id = 'usr-....';

-- Exactly the rows you meant, and no other. Anything else: rollback.
select user_id, oidc_issuer, oidc_subject, email from app_user;

commit;
```

The issuer must match `HIVEKEEPER_OIDC_ISSUER` **character for character**, trailing slash included — the
gateway compares the `iss` claim to it as a string.

### 5. Switch the overlay and restart

```sh
docker compose --env-file .env.prod \
  -f docker-compose.prod.yml -f docker-compose.prod.authentik.yml up -d
```

Note that the Keycloak services are not stopped by this — they are simply no longer part of the composition.
Take them down deliberately, once sign-in works:

```sh
docker compose --env-file .env.prod \
  -f docker-compose.prod.yml -f docker-compose.prod.keycloak.yml stop keycloak keycloak-init keycloak-db
```

Leave the `keycloak-db-data` volume in place until you are confident. It is the only copy of the old
identities, and it costs nothing to keep.

### 6. Verify, in this order

1. `GET /api/mode` reports the Authentik issuer and `hive-gateway` as the client id.
2. Sign in at the console. You land on your organization with your existing role — that is the re-key working.
3. The fleet still lists devices, and the audit log still attributes past actions to you.

If sign-in succeeds but the console says you belong to no organization, the re-key did not match: the `sub` in
your token is not what you wrote into `oidc_subject`. Check the provider's subject mode is *Based on the
User's ID* rather than the default hash.

### Rolling back

```sh
docker compose --env-file .env.prod \
  -f docker-compose.prod.yml -f docker-compose.prod.keycloak.yml up -d
```

then restore the `oidc_issuer` / `oidc_subject` values you recorded in step 1. Nothing else has to be undone —
no membership, grant or device record was touched.

## Verifying against a real instance

`AuthentikLiveIT` runs the gateway's Authentik client against a live server. It is skipped unless you ask for
it, so it never gates CI:

```bash
AUTHENTIK_IT_URL=http://localhost:9000 AUTHENTIK_IT_TOKEN=$HIVEKEEPER_AUTHENTIK_API_TOKEN   ./gradlew :hive-gateway:test --tests '*AuthentikLiveIT*'
```

The mocked unit tests pin the conversation we believe Authentik has; this one pins the conversation it
actually has. Run it after any Authentik version bump.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| Gateway exits at startup: `No qualifying bean of type 'IdpAdminClient'` | `hivekeeper.idp` is set to something that is neither `keycloak` nor `authentik` (a typo fails loudly rather than silently picking the wrong IdP). |
| First admin is created but cannot sign in | Provider subject mode is not `user_id`. |
| `Authentik created 'x' but issued no recovery link ... set a recovery flow` | No recovery flow set as the brand's default. The named account was left behind — delete it in Authentik before retrying. |
| Token rejected: `iss` mismatch | `HIVEKEEPER_OIDC_ISSUER` must be the URL the **browser** logs in at, not the container name. The gateway reaches the API over the container network separately. |
| Token rejected, JWKS empty | The provider has no asymmetric signing key, so Authentik signed with HS256. Give it a certificate keypair. |
| Every API call answers 403 right after a first boot | `AUTHENTIK_BOOTSTRAP_TOKEN` has to be set on the **worker** as well as the server — the worker is what applies the blueprint that creates the token. The compose file sets both. |
| `creating the Authentik user failed: HTTP 400 {"username":["This field is required."]}` on a request that clearly sent one | Authentik's router drops chunked request bodies and rejects the JDK client's h2c upgrade. `AuthentikAdminClient` pins HTTP/1.1 and buffers, so this should not resurface unless that is changed. |
