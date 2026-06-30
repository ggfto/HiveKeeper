# Plan A — Automated agent enrollment (slice 1: token → CSR → signed cert)

> **Status: slice 1 SHIPPED.** Built as the `enroll` package in `hive-gateway` (`CertificateAuthority` +
> file-backed `FileCertificateAuthority`, `EnrollmentCertificateController` at
> `POST /api/enrollments/{token}/certificate`), `TenantStore.markEnrollmentConsumed` + Flyway **V11**
> (`consumed_at` + UPDATE grant), and `EnrollmentBootstrap` in `hive-agent`. CA custody is **file-backed**
> (`HIVEKEEPER_CA_KEYSTORE`, dev/self-hosted). Tested: `FileCertificateAuthorityTest`,
> `EnrollmentCertificateControllerTest`, `EnrollmentBootstrapTest`, + a Postgres-IT consume check.
> **Deviations from this plan:** CA loads a PKCS12 keystore (reuses `dev-pki/ca.p12`) instead of separate
> `HIVEKEEPER_CA_CERT`/`_KEY` PEMs; the cert is returned as a PEM bundle (leaf then CA) rather than a JSON
> `{certificatePem, caChainPem}`. Slice 2 (auto-renewal, revocation, intermediate CA, KMS/HSM) is below.

## Goal

Replace pre-provisioned mTLS certs with a bootstrap: given a one-time enrollment token, the agent generates its
own keypair, sends a CSR, and the gateway (holding a CA) signs and returns the client cert + CA chain. The agent
writes them to its keystore/truststore and connects via mTLS. **Slice 1 deliberately excludes auto-renewal and
revocation** (slice 2).

## Current state (what exists)

- `POST /api/enrollments` mints a one-time token scoped to `tenantId` / `siteId`, recorded in the
  `agent_enrollment` table (`token`, `agent_id`, `tenant_id`).
- mTLS today: the agent presents a **pre-provisioned** client cert (`scripts/gen-dev-pki.ps1` makes a CA +
  per-agent client certs + the gateway server cert + truststore). `AgentAuthInterceptor` derives the `agentId`
  from the cert **CN** and the tenant from the enrollment record (server-side, never from the client).
- Bootstrap fallback already accepted at the WS handshake: a bearer enrollment token query param
  (`?token=…`) lets an agent connect before it has a cert.
- `AgentConfig` already has `HIVEKEEPER_TLS_KEYSTORE` / `_TRUSTSTORE` (+ passwords); `TlsSupport` loads them.

So the **token** half exists; the **token → CSR → cert** half is entirely new.

## Key decision (must settle before building): CA custody

Where does the **CA private key** live, and who signs?

- **Slice 1 (proposed):** a **file-based CA** — the gateway loads a CA cert + key from configured paths
  (`HIVEKEEPER_CA_CERT`, `HIVEKEEPER_CA_KEY`), reusing the CA that `gen-dev-pki.ps1` already produces.
  **Dev/self-hosted only.** Documented as such.
- **Production (deferred):** an intermediate CA whose key sits in a **KMS/HSM** (or a dedicated signing
  service), short-lived leaf certs, and a revocation story. Out of scope for slice 1 — but slice 1 must not
  hard-code assumptions that block it (keep signing behind a `CertificateAuthority` interface).

## Scope of slice 1

Bootstrap a *new* agent end-to-end (token → CSR → cert → mTLS connect). **Not** in slice 1: auto-renewal,
revocation/CRL/OCSP, intermediate CA, KMS/HSM, SAN beyond CN.

## Design

### Tooling

X.509 CSR parsing + signing needs **BouncyCastle** (`bcpkix-jdk18on`): JCA alone is awkward for CSRs.
New dependency in **hive-gateway** (sign) and **hive-agent** (generate keypair + build CSR).

### Identity is server-assigned

The gateway sets the issued cert's **subject CN = the `agentId` from the enrollment record**, ignoring whatever
CN the CSR carries (it only trusts the CSR's public key). This keeps the existing `AgentAuthInterceptor` CN →
agentId mapping intact and prevents an agent from choosing its own identity.

### Bootstrap endpoint

`POST /api/enrollments/{token}/certificate`
- **Auth:** the one-time token itself (no user, no mTLS — the agent has no cert yet).
- **Body:** the CSR (PEM).
- **Validate:** token exists, **unconsumed**, unexpired.
- **Sign:** CA issues a leaf cert — `CN=<agentId>`, EKU `clientAuth`, validity (e.g. 90 days), the CSR's public
  key. Mark the token **consumed** (one-time; transactional). 
- **Return:** `{ certificatePem, caChainPem }`.

### Agent bootstrap state machine (AgentMain)

On startup: if the keystore is absent/empty **and** an enrollment token + bootstrap URL are configured:
1. generate an RSA-2048 (or EC) keypair locally;
2. build a CSR (BouncyCastle);
3. `POST` it to `HIVEKEEPER_ENROLLMENT_URL` + `/api/enrollments/{token}/certificate` over HTTPS (validating the
   gateway's server cert against a bootstrap CA bundle);
4. write a **PKCS12 keystore** (key + issued cert) and a **truststore** (CA chain) at the configured paths;
5. proceed to the normal mTLS connect.
If a keystore already exists, skip bootstrap and use it.

## Module changes

| Module | Change |
|---|---|
| `hive-gateway` | `build.gradle.kts`: + `bcpkix-jdk18on`. New `CertificateAuthority` (interface + file-backed impl: load CA cert/key, `sign(PKCS10 csr, String cn, Duration validity) -> X509Certificate`). New endpoint `POST /api/enrollments/{token}/certificate`. `TenantStore`/store: `enrollmentByToken` (exists) + `markEnrollmentConsumed(token)`. Flyway **V11**: add `consumed_at timestamptz` to `agent_enrollment`. Config: `HIVEKEEPER_CA_CERT` / `HIVEKEEPER_CA_KEY`. |
| `hive-agent` | `build.gradle.kts`: + `bcpkix-jdk18on`. New `EnrollmentBootstrap` (keypair + CSR + HTTP POST via `java.net.http.HttpClient` + write PKCS12 keystore/truststore). `AgentConfig`: `HIVEKEEPER_ENROLLMENT_TOKEN`, `HIVEKEEPER_ENROLLMENT_URL`. `AgentMain`: run bootstrap when no keystore + a token is set, before connecting. |
| `scripts` | `gen-dev-pki.ps1`: ensure it can emit a standalone **CA cert + key** for the gateway to load (it already builds a CA). |
| `docs` | On ship: flip `agent-protocol.md` "Not yet → automated certificate enrollment" to done. |

## Testing

- **gateway**: `CertificateAuthority` unit test — signing a CSR yields a cert that chains to the CA, has
  `CN=<agentId>` and EKU `clientAuth`. Endpoint slice test — valid token → 200 + cert; reused / expired /
  unknown token → 4xx; a CSR whose CN ≠ the enrollment's agentId is **overridden** (issued CN = agentId).
- **agent**: CSR generation unit test (keypair → CSR parses, expected subject); bootstrap flow against a stub
  HTTP endpoint, then assert the written PKCS12 keystore is loadable by `TlsSupport`.
- **loopback** (optional): token → bootstrap → mTLS connect, mirroring `WebSocketLoopbackTest`.

## Risks / open questions

- **CA key custody in production** (the big one) — KMS/HSM/intermediate; slice 1 = file, dev-only.
- **Renewal & revocation** — deferred to slice 2 (agent re-requests before expiry over its current mTLS;
  revocation via short-lived certs + re-bootstrap, or CRL/OCSP).
- Token consumption atomicity (single DB transaction; reject races).
- Keypair type (RSA-2048 vs EC P-256) and cert validity window / clock skew.

## Slice 2+ (deferred)

Auto-renewal (mTLS-authenticated re-issue before expiry), revocation, intermediate CA, KMS/HSM custody, richer
SANs, an admin "re-enroll / revoke agent" action.

## Size estimate

**Large** — comparable to the PPSK Caminho B or alert-delivery subsystems in code volume, and **more
security-sensitive** because it introduces a signing authority (CA key custody) into the gateway plus a new
crypto dependency and an agent boot state machine. The hard part is the custody decision, not the typing.
