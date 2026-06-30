---
title: Authentication
description: HiveKeeper runs fine without Keycloak — pick solo, tenant-key, or OIDC.
---

The `hive-gateway` control plane supports user login via OpenID Connect (Keycloak in dev), but **OIDC is
entirely optional** — it is gated behind the Spring `oidc` profile. Leave that profile off and there is no
Keycloak dependency anywhere: `OidcSecurityConfig`, the first-run `SetupService`, and `KeycloakAdminClient`
are all `@Profile("oidc")`, so without it `DefaultSecurityConfig` takes over (`permitAll`, with the
controllers enforcing the `X-Tenant-Key` service principal).

| Mode | How to start | Sign-in | Auth model |
| --- | --- | --- | --- |
| **Solo** (single user, single AP, local) | `HIVEKEEPER_SOLO=true` (no `oidc` profile) | None | Every request is the local owner |
| **Tenant-key** (multi-org, dev/demo) | `SPRING_PROFILES_ACTIVE=demo` | Web "dev mode" toggle | Static `X-Tenant-Key` per tenant |
| **OIDC** (per-user roles) | `SPRING_PROFILES_ACTIVE=postgres,oidc` + Keycloak | OIDC login | JWT identity + DB-backed org/site/group roles |

## Solo mode — the simplest way to run without Keycloak

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-solo.ps1   # full stack
# or just the gateway:  HIVEKEEPER_SOLO=true ./gradlew :hive-gateway:run
```

No login screen, no organizations — the gateway authorizes every request as the owner of an implicit
`local` tenant, and the web app learns this at boot from `/api/mode` and skips the sign-in gate entirely.
Best for managing a single local AP without standing up an IdP.

:::caution
Solo mode disables authentication. Run it only on a trusted machine and keep the gateway bound to
`localhost` (the default in `application.properties`).
:::

## Tenant-key mode — the multi-org console without an IdP

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-local.ps1   # full stack
# or just the gateway:  SPRING_PROFILES_ACTIVE=demo ./gradlew :hive-gateway:run
```

The `demo` profile seeds well-known tenants (`acme` / `globex`) with keys `acme-key` / `globex-key`. In the
web UI, enable **dev mode** so the console sends `X-Tenant-Key` instead of a bearer token. This is
local-dev convenience only — the keys are public and in source, so **never enable `demo` in production**.

## What you give up without OIDC

Per-user login, the first-run setup wizard (`SetupService` is OIDC-only), and per-user authorization by
scoped role — all of which depend on an IdP authenticating human identities. For a full deployment, enable
OIDC: `scripts/dev-keycloak.ps1` brings up a dev Keycloak + realm, then run with
`SPRING_PROFILES_ACTIVE=postgres,oidc`. In that setup you can also set `HIVEKEEPER_TENANTKEY_ENABLED=false`
so a leaked static key cannot bypass per-user authorization.
