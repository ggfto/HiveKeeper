#!/usr/bin/env bash
# Configures a PRODUCTION Keycloak for HiveKeeper: the realm, the console's public client, and — when you
# supply GitHub credentials — "Sign in with GitHub".
#
# This is a script and not a realm-import JSON on purpose. Keycloak does NOT substitute ${env.VAR} placeholders
# in an imported realm (verified against 26.0: the placeholders survive verbatim and fall back to their
# defaults), and every value that matters here is deployment-specific — the console's URL, the GitHub client
# secret. A realm file would therefore have to be edited by hand by every self-hoster.
#
# Idempotent: safe to re-run on every stack start. It creates what is missing and updates what has drifted, so
# changing HIVEKEEPER_CONSOLE_URL and bringing the stack up again just works.
#
# Runs inside the Keycloak image (it needs kcadm.sh). See docker-compose.prod.yml.
set -euo pipefail

KC_URL="${KC_URL:-http://keycloak:8080}"
REALM="${HIVEKEEPER_REALM:-hivekeeper}"
CLIENT_ID="${HIVEKEEPER_OIDC_CLIENT_ID:-hive-gateway}"
# No apostrophe in this message: bash still processes quotes inside ${VAR:?word}, so one would open a string
# that never closes and the script would die with a parse error before it could tell you what was missing.
CONSOLE_URL="${HIVEKEEPER_CONSOLE_URL:?set it to the public URL of the console, e.g. https://hivekeeper.example.org}"
ADMIN="${KEYCLOAK_ADMIN:?set KEYCLOAK_ADMIN}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:?set KEYCLOAK_ADMIN_PASSWORD}"

GITHUB_CLIENT_ID="${HIVEKEEPER_GITHUB_CLIENT_ID:-}"
GITHUB_CLIENT_SECRET="${HIVEKEEPER_GITHUB_CLIENT_SECRET:-}"

kcadm() { /opt/keycloak/bin/kcadm.sh "$@"; }

echo ">> waiting for Keycloak at ${KC_URL}"
for _ in $(seq 1 60); do
  if kcadm config credentials --server "$KC_URL" --realm master \
      --user "$ADMIN" --password "$ADMIN_PASSWORD" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
kcadm config credentials --server "$KC_URL" --realm master --user "$ADMIN" --password "$ADMIN_PASSWORD"

# --- realm ---------------------------------------------------------------------------------------------
if kcadm get "realms/${REALM}" >/dev/null 2>&1; then
  echo ">> realm '${REALM}' exists"
else
  echo ">> creating realm '${REALM}'"
  kcadm create realms -s "realm=${REALM}" -s enabled=true -s displayName=HiveKeeper
fi

# --- the console's public client ------------------------------------------------------------------------
# Public (no secret): it runs in a browser, where a secret cannot be kept. Auth-code + PKCE(S256) is what makes
# that safe. Direct access grants stay OFF — nothing should be exchanging a username and password for a token.
CLIENT_UUID="$(kcadm get clients -r "$REALM" -q "clientId=${CLIENT_ID}" --fields id --format csv --noquotes)"

CLIENT_ARGS=(
  -s "clientId=${CLIENT_ID}"
  -s enabled=true
  -s publicClient=true
  -s standardFlowEnabled=true
  -s directAccessGrantsEnabled=false
  -s serviceAccountsEnabled=false
  -s "redirectUris=[\"${CONSOLE_URL}/*\"]"
  -s "webOrigins=[\"${CONSOLE_URL}\"]"
  -s 'attributes."pkce.code.challenge.method"=S256'
)

if [ -z "$CLIENT_UUID" ]; then
  echo ">> creating client '${CLIENT_ID}' for ${CONSOLE_URL}"
  kcadm create clients -r "$REALM" "${CLIENT_ARGS[@]}"
  CLIENT_UUID="$(kcadm get clients -r "$REALM" -q "clientId=${CLIENT_ID}" --fields id --format csv --noquotes)"
else
  echo ">> updating client '${CLIENT_ID}' for ${CONSOLE_URL}"
  kcadm update "clients/${CLIENT_UUID}" -r "$REALM" "${CLIENT_ARGS[@]}"
fi

# The gateway rejects a token whose `aud` does not name it (audience confusion: the same realm mints tokens for
# the account console and every other client, all correctly signed by the same issuer). It accepts `azp` too, so
# this mapper is defence in depth rather than strictly required — but it costs nothing and closes the weaker path.
if kcadm get "clients/${CLIENT_UUID}/protocol-mappers/models" -r "$REALM" --fields name --format csv --noquotes \
    | grep -qx "hivekeeper-audience"; then
  echo ">> audience mapper exists"
else
  echo ">> adding the audience mapper"
  kcadm create "clients/${CLIENT_UUID}/protocol-mappers/models" -r "$REALM" \
    -s name=hivekeeper-audience \
    -s protocol=openid-connect \
    -s protocolMapper=oidc-audience-mapper \
    -s "config.\"included.client.audience\"=${CLIENT_ID}" \
    -s 'config."access.token.claim"=true'
fi

# --- Sign in with GitHub (optional) -----------------------------------------------------------------------
# GitHub is OAuth2, NOT OpenID Connect: it issues an opaque token, no id_token, and publishes no JWKS for user
# login. The gateway validates JWTs by signature, issuer and audience, so it can never accept a GitHub token
# directly. Keycloak brokers instead — it authenticates the user against GitHub and mints one of ITS OWN tokens,
# which the gateway already knows how to validate. Nothing in the gateway changes.
#
# In your GitHub OAuth App, the Authorization callback URL must be exactly:
#   <keycloak public URL>/realms/<realm>/broker/github/endpoint
if [ -n "$GITHUB_CLIENT_ID" ] && [ -n "$GITHUB_CLIENT_SECRET" ]; then
  GITHUB_ARGS=(
    -s alias=github
    -s providerId=github
    -s enabled=true
    # GitHub verifies the addresses it hands us, and `user:email` asks for the primary one. Without trustEmail
    # Keycloak makes every GitHub user confirm an address it already knows is theirs.
    -s trustEmail=true
    -s "config.clientId=${GITHUB_CLIENT_ID}"
    -s "config.clientSecret=${GITHUB_CLIENT_SECRET}"
    -s "config.defaultScope=user:email"
  )
  if kcadm get identity-provider/instances/github -r "$REALM" >/dev/null 2>&1; then
    echo ">> updating the GitHub identity provider"
    kcadm update identity-provider/instances/github -r "$REALM" "${GITHUB_ARGS[@]}"
  else
    echo ">> adding the GitHub identity provider"
    kcadm create identity-provider/instances -r "$REALM" "${GITHUB_ARGS[@]}"
  fi
  echo ">> GitHub OAuth App callback URL must be: <keycloak-public-url>/realms/${REALM}/broker/github/endpoint"
else
  echo ">> no GitHub credentials (HIVEKEEPER_GITHUB_CLIENT_ID/_SECRET) — username+password sign-in only"
fi

echo ">> Keycloak is configured."
echo ">> Next: open ${CONSOLE_URL} and complete first-run setup with the token from the gateway's log."
