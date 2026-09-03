# Authentik Integration for HiveKeeper

HiveKeeper now supports **Authentik** as an identity provider alternative to Keycloak.

## 🚀 Quick Start

### 1. Start with Authentik

```bash
docker compose -f docker-compose.yml \
               -f docker-compose.postgres.yml \
               -f docker-compose.authentik.yml up -d --build
```

### 2. Configure Authentik

1. Access the admin panel: http://localhost:9000/if/admin/
2. Initial login: `akadmin` / `admin` (or the password set in `AUTHENTIK_BOOTSTRAP_PASSWORD`)
3. Create an API Token:
   - **Authentik Admin** → **Tokens & App passwords** → **Create Token**
   - User: akadmin
   - Intent: API Token
   - Copy the generated token

4. Configure the environment variable:
```bash
export HIVEKEEPER_AUTHENTIK_API_TOKEN="your-token-here"
```

5. Restart the gateway to apply the token:
```bash
docker compose restart gateway
```

### 3. Configure the OAuth2/OIDC Application

Run the bootstrap script (or configure manually via the panel):

```bash
docker compose exec authentik-server /bootstrap.sh
```

**OR manually via the admin panel:**

1. **Create Provider** (Providers → Create):
   - Name: `hivekeeper-provider`
   - Type: `OAuth2/OpenID Provider`
   - Authorization flow: `default-authentication-flow`
   - Client type: `Public`
   - Client ID: `hive-gateway`
   - Redirect URIs: `http://localhost:3000/*`
   - Subject mode: `Based on the User's ID`

2. **Create Application** (Applications → Create):
   - Name: `HiveKeeper`
   - Slug: `hivekeeper`
   - Provider: `hivekeeper-provider`
   - Launch URL: `http://localhost:3000`

### 4. First Setup

Access the console: http://localhost:3000

The gateway will display the **setup token** in the logs:
```bash
docker compose logs gateway | grep "setup token"
```

Use the token to create the first organization and the first admin.

## 📖 Architecture

```
┌─────────────┐         ┌──────────────┐         ┌─────────────┐
│   Browser   │◄───────►│  Authentik   │         │  PostgreSQL │
│  (Console)  │  OIDC   │   Server     │◄───────►│  (identities│
└─────────────┘         └──────────────┘         │   + config) │
       │                       ▲                  └─────────────┘
       │                       │
       │                       │ Admin API
       │                       │ (create users)
       │                       │
       ▼                       │
┌─────────────┐         ┌──────────────┐
│  HiveKeeper │◄───────►│  PostgreSQL  │
│   Gateway   │  JWT    │  (app data)  │
└─────────────┘ validation  └──────────┘
```

## 🔑 Differences: Keycloak vs Authentik

| Aspect | Keycloak | Authentik |
|---------|----------|-----------|
| **Spring Profile** | `postgres,oidc` | `postgres,oidc,oidc-authentik` |
| **Admin Credentials** | Username + Password | API Token |
| **Issuer URL** | `/realms/{realm}` | `/application/o/{app}/` |
| **JWKS URL** | `/realms/{realm}/protocol/openid-connect/certs` | `/application/o/{app}/jwks/` |
| **User Creation** | Via `kcadm.sh` or REST API with realm admin | Via REST API with token |
| **Federated Login** | Native brokers (GitHub, Google, etc.) | Sources (GitHub, Google, LDAP, etc.) |

## 📝 Production

Add to your `docker-compose.prod.yml` or configure separately:

### Required Environment Variables

```env
# Authentik
AUTHENTIK_SECRET_KEY=<generated-with-openssl-rand-hex-32>
AUTHENTIK_BOOTSTRAP_PASSWORD=<initial-admin-password>
AUTHENTIK_BOOTSTRAP_TOKEN=<optional-api-token>
AUTHENTIK_DB_PASSWORD=<postgres-password-for-authentik>

# HiveKeeper Gateway
HIVEKEEPER_OIDC_ISSUER=https://auth.yourdomain.com/application/o/hivekeeper/
HIVEKEEPER_OIDC_JWK_SET_URI=https://auth.yourdomain.com/application/o/hivekeeper/jwks/
HIVEKEEPER_AUTHENTIK_BASE_URL=https://auth.yourdomain.com
HIVEKEEPER_AUTHENTIK_API_TOKEN=<authentik-api-token>
HIVEKEEPER_CONSOLE_URL=https://hivekeeper.yourdomain.com
```

### Reverse Proxy (Caddy)

```caddyfile
auth.yourdomain.com {
    reverse_proxy authentik-server:9000
}
```

## 💻 Code

The integration was implemented as an **abstraction over identity providers**:

- **Interface**: `IdpAdminClient` (common contract)
- **Implementations**:
  - `KeycloakAdminClient` (profile: `oidc` or `oidc-keycloak`)
  - `AuthentikAdminClient` (profile: `oidc-authentik`)
- **Consumer**: `SetupService` (injects `IdpAdminClient` via Spring)

### Example: User Creation

```java
// Generic code - works with both IdPs
String userId = idpAdminClient.createUser(
    "johndoe",
    "john@example.com",
    "initialPassword",
    "John Doe",
    false  // temporary password
);
```

## 🔄 Migration from Keycloak to Authentik

There is no automatic user migration. To switch IdPs:

1. **Export** users from Keycloak (via Admin Console or REST API)
2. **Recreate** users in Authentik via script:
```bash
for user in users.json; do
  curl -X POST https://auth.yourdomain.com/api/v3/core/users/ \
    -H "Authorization: Bearer $TOKEN" \
    -d "$user"
done
```
3. Update the profile and environment variables
4. Restart the stack

## 🐛 Troubleshooting

### "Authentik did not return the new user's pk"
- Verify the token has admin permissions
- Check Authentik logs: `docker compose logs authentik-server`

### "401 Unauthorized" on setup
- Confirm `HIVEKEEPER_AUTHENTIK_API_TOKEN` is configured
- Test the token manually:
```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:9000/api/v3/core/users/
```

### Gateway doesn't validate JWTs
- Confirm `HIVEKEEPER_OIDC_ISSUER` matches the token issuer
- Inspect a JWT at https://jwt.io and check the `iss` claim
- Verify JWKS is accessible:
```bash
curl http://localhost:9000/application/o/hivekeeper/jwks/
```

### Users created but can't login
- Verify the application redirect URIs match your console URL
- Check the authorization flow is set correctly
- Confirm the provider is assigned to the application

### Bootstrap script fails
- Ensure Authentik is fully started: `docker compose logs authentik-server`
- Verify the API token is valid
- Check network connectivity between containers

### JWT audience validation fails
- Confirm `HIVEKEEPER_OIDC_AUDIENCE` matches the client ID
- Add an audience mapper in the provider if needed
- Check the JWT `aud` or `azp` claim

## 📚 References

- [Authentik Documentation](https://docs.goauthentik.io/)
- [Authentik API Reference](https://docs.goauthentik.io/developer-docs/api)
- [OAuth2/OIDC Provider Setup](https://docs.goauthentik.io/docs/providers/oauth2/)
- [Authentik Flows](https://docs.goauthentik.io/docs/flow/)
- [Integration Patterns](https://docs.goauthentik.io/docs/providers/oauth2/client_credentials)
