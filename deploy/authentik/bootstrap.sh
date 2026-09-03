#!/usr/bin/env bash
# Configures a PRODUCTION Authentik for HiveKeeper: the OAuth2/OIDC provider, the application, and the flow.
#
# Idempotent: safe to re-run on every stack start. It creates what is missing and updates what has drifted.
#
# Runs inside a container with curl and jq available. See docker-compose.prod.yml.
set -euo pipefail

AUTHENTIK_URL="${AUTHENTIK_URL:-http://authentik-server:9000}"
API_TOKEN="${HIVEKEEPER_AUTHENTIK_API_TOKEN:?set HIVEKEEPER_AUTHENTIK_API_TOKEN}"
CONSOLE_URL="${HIVEKEEPER_CONSOLE_URL:?set it to the public URL of the console, e.g. https://hivekeeper.example.org}"
APP_NAME="${HIVEKEEPER_APP_NAME:-hivekeeper}"
CLIENT_ID="${HIVEKEEPER_OIDC_CLIENT_ID:-hive-gateway}"

AUTH_HEADER="Authorization: Bearer ${API_TOKEN}"
CONTENT_JSON="Content-Type: application/json"

echo ">> waiting for Authentik at ${AUTHENTIK_URL}"
for _ in $(seq 1 60); do
  if curl -sf -H "${AUTH_HEADER}" "${AUTHENTIK_URL}/api/v3/core/applications/" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

# --- OAuth2/OIDC Provider ------------------------------------------------------------------------
# Check if provider exists
PROVIDER_RESPONSE=$(curl -sf -H "${AUTH_HEADER}" \
  "${AUTHENTIK_URL}/api/v3/providers/oauth2/?name=${APP_NAME}-provider" || echo '{"results":[]}')

PROVIDER_ID=$(echo "$PROVIDER_RESPONSE" | jq -r '.results[0].pk // empty')

PROVIDER_PAYLOAD=$(cat <<EOF
{
  "name": "${APP_NAME}-provider",
  "authorization_flow": "$(curl -sf -H "${AUTH_HEADER}" \
    "${AUTHENTIK_URL}/api/v3/flows/instances/?slug=default-authentication-flow" | \
    jq -r '.results[0].pk')",
  "client_type": "public",
  "client_id": "${CLIENT_ID}",
  "redirect_uris": "${CONSOLE_URL}/*",
  "sub_mode": "user_id",
  "issuer_mode": "per_provider",
  "signing_key": "$(curl -sf -H "${AUTH_HEADER}" \
    "${AUTHENTIK_URL}/api/v3/crypto/certificatekeypairs/?has_key=true&ordering=name" | \
    jq -r '.results[0].pk')"
}
EOF
)

if [ -z "$PROVIDER_ID" ]; then
  echo ">> creating OAuth2 provider '${APP_NAME}-provider'"
  PROVIDER_ID=$(curl -sf -X POST -H "${AUTH_HEADER}" -H "${CONTENT_JSON}" \
    -d "${PROVIDER_PAYLOAD}" \
    "${AUTHENTIK_URL}/api/v3/providers/oauth2/" | jq -r '.pk')
else
  echo ">> updating OAuth2 provider '${APP_NAME}-provider'"
  curl -sf -X PUT -H "${AUTH_HEADER}" -H "${CONTENT_JSON}" \
    -d "${PROVIDER_PAYLOAD}" \
    "${AUTHENTIK_URL}/api/v3/providers/oauth2/${PROVIDER_ID}/" >/dev/null
fi

# --- Application --------------------------------------------------------------------------------
APP_RESPONSE=$(curl -sf -H "${AUTH_HEADER}" \
  "${AUTHENTIK_URL}/api/v3/core/applications/?slug=${APP_NAME}" || echo '{"results":[]}')

APP_UUID=$(echo "$APP_RESPONSE" | jq -r '.results[0].pk // empty')

APP_PAYLOAD=$(cat <<EOF
{
  "name": "HiveKeeper",
  "slug": "${APP_NAME}",
  "provider": ${PROVIDER_ID},
  "meta_launch_url": "${CONSOLE_URL}",
  "open_in_new_tab": true
}
EOF
)

if [ -z "$APP_UUID" ]; then
  echo ">> creating application '${APP_NAME}' for ${CONSOLE_URL}"
  curl -sf -X POST -H "${AUTH_HEADER}" -H "${CONTENT_JSON}" \
    -d "${APP_PAYLOAD}" \
    "${AUTHENTIK_URL}/api/v3/core/applications/" >/dev/null
else
  echo ">> updating application '${APP_NAME}' for ${CONSOLE_URL}"
  curl -sf -X PUT -H "${AUTH_HEADER}" -H "${CONTENT_JSON}" \
    -d "${APP_PAYLOAD}" \
    "${AUTHENTIK_URL}/api/v3/core/applications/${APP_UUID}/" >/dev/null
fi

echo ">> Authentik configuration complete"
echo ">> OAuth2/OIDC Issuer: ${AUTHENTIK_URL}/application/o/${APP_NAME}/"
echo ">> JWKS URI: ${AUTHENTIK_URL}/application/o/${APP_NAME}/jwks/"
echo ">> Client ID: ${CLIENT_ID}"
