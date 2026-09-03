# Authentik Integration

HiveKeeper now supports **Authentik** as an identity provider alternative to Keycloak.

## 🚀 Quick Start

```bash
# 1. Start the stack with Authentik
docker compose -f docker-compose.yml \
               -f docker-compose.postgres.yml \
               -f docker-compose.authentik.yml up -d --build

# 2. Access Authentik admin panel
# URL: http://localhost:9000/if/admin/
# Login: akadmin / admin (or AUTHENTIK_BOOTSTRAP_PASSWORD)

# 3. Create an API Token in Authentik
# Authentik Admin → Tokens & App passwords → Create Token
# User: akadmin | Intent: API Token

# 4. Configure the token and restart the gateway
export HIVEKEEPER_AUTHENTIK_API_TOKEN="your-token-here"
docker compose restart gateway

# 5. Access HiveKeeper console
# URL: http://localhost:3000
# Use the setup token from gateway logs to create the first org
```

## 📖 Complete Documentation

See [docs/authentik.md](docs/authentik.md) for:
- Detailed Authentik setup
- Production environment variables
- Keycloak to Authentik migration
- Troubleshooting
- Integration architecture

## 🔑 Key Differences

### Keycloak
```bash
docker-compose.keycloak.yml
SPRING_PROFILES_ACTIVE=postgres,oidc
```

### Authentik
```bash
docker-compose.authentik.yml
SPRING_PROFILES_ACTIVE=postgres,oidc,oidc-authentik
```

## 🏗️ Architecture

The integration uses abstraction through interfaces:

- **Interface**: `IdpAdminClient` (common contract)
- **Implementations**:
  - `KeycloakAdminClient` (profile: `oidc` or `oidc-keycloak`)
  - `AuthentikAdminClient` (profile: `oidc-authentik`)

The gateway code is IdP-agnostic — Spring Profile determines which implementation is injected.

## 📝 Notes

- **First access**: Authentik creates the `akadmin` user on first initialization
- **API Token**: Required for the gateway to create users via Admin API
- **Bootstrap script**: `deploy/authentik/bootstrap.sh` configures provider/app automatically
- **Compatibility**: This change is **backward compatible** — Keycloak continues to work

## 🐛 Common Issues

### Gateway can't create users
```bash
# Check if token is configured
docker compose logs gateway | grep AUTHENTIK_API_TOKEN

# Test token manually
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:9000/api/v3/core/users/
```

### JWT tokens are not validated
```bash
# Check configured issuer
docker compose exec gateway env | grep OIDC_ISSUER

# Confirm JWKS is accessible
curl http://localhost:9000/application/o/hivekeeper/jwks/
```

---

For more details, see the [complete documentation](docs/authentik.md).
