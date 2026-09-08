#!/usr/bin/env bash
# Configures an Authentik instance for HiveKeeper: the OAuth2/OIDC provider, the application, and the brand's
# recovery flow.
#
# Idempotent: safe to re-run on every stack start. It creates what is missing and updates what has drifted.
#
# Runs as the `authentik-init` service in docker-compose.authentik.yml (an image with curl + jq). It also runs
# standalone against an Authentik you already operate:
#
#   AUTHENTIK_URL=https://sso.example.org \
#   HIVEKEEPER_AUTHENTIK_API_TOKEN=... \
#   HIVEKEEPER_CONSOLE_URL=https://hivekeeper.example.org \
#     ./deploy/authentik/bootstrap.sh
#
# Two settings here are load-bearing, not cosmetic:
#   * sub_mode=user_id — the gateway stores the user's pk as their OIDC subject when it provisions them. Under
#     Authentik's DEFAULT sub mode (a salted hash of the id) the `sub` in the token would never match, and the
#     first admin would be created and then be unable to sign in.
#   * the brand's recovery flow — adding a teammate asks Authentik for a one-time recovery link so the admin
#     never holds a working password for someone else's account. Authentik refuses that call unless a recovery
#     flow is the active brand's default, so adding a teammate fails until one is bound.
set -euo pipefail

AUTHENTIK_URL="${AUTHENTIK_URL:-http://authentik-server:9000}"
API_TOKEN="${HIVEKEEPER_AUTHENTIK_API_TOKEN:?set HIVEKEEPER_AUTHENTIK_API_TOKEN}"
CONSOLE_URL="${HIVEKEEPER_CONSOLE_URL:?set it to the public URL of the console, e.g. https://hivekeeper.example.org}"
APP_NAME="${HIVEKEEPER_APP_NAME:-hivekeeper}"
CLIENT_ID="${HIVEKEEPER_OIDC_CLIENT_ID:-hive-gateway}"

AUTH_HEADER="Authorization: Bearer ${API_TOKEN}"
CONTENT_JSON="Content-Type: application/json"

api() { curl -sf -H "${AUTH_HEADER}" "$@"; }

echo ">> waiting for Authentik at ${AUTHENTIK_URL}"
for _ in $(seq 1 60); do
  if api "${AUTHENTIK_URL}/api/v3/core/applications/" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
api "${AUTHENTIK_URL}/api/v3/core/applications/" >/dev/null \
  || { echo "!! Authentik did not answer at ${AUTHENTIK_URL} (or the API token is wrong)"; exit 1; }

# --- prerequisites -------------------------------------------------------------------------------
AUTH_FLOW=$(api "${AUTHENTIK_URL}/api/v3/flows/instances/?slug=default-authentication-flow" \
  | jq -r '.results[0].pk // empty')
[ -n "${AUTH_FLOW}" ] || { echo "!! no default-authentication-flow in this Authentik"; exit 1; }

SIGNING_KEY=$(api "${AUTHENTIK_URL}/api/v3/crypto/certificatekeypairs/?has_key=true&ordering=name" \
  | jq -r '.results[0].pk // empty')
# Without an asymmetric signing key Authentik signs tokens with HS256 and the gateway's JWKS lookup finds
# nothing to verify against.
[ -n "${SIGNING_KEY}" ] || { echo "!! no certificate keypair to sign tokens with"; exit 1; }

# The scope mappings that put openid/email/profile claims in the token. Their REST path moved between
# Authentik releases, so try the current one and fall back to the old one.
scope_mappings() {
  local path
  for path in "provider/scope" "scope"; do
    local out
    out=$(api "${AUTHENTIK_URL}/api/v3/propertymappings/${path}/?ordering=scope_name" 2>/dev/null) || continue
    local pks
    pks=$(echo "${out}" | jq -c '[.results[]
      | select(.scope_name == "openid" or .scope_name == "email" or .scope_name == "profile") | .pk]')
    if [ "${pks}" != "[]" ] && [ -n "${pks}" ]; then
      echo "${pks}"
      return 0
    fi
  done
  echo "[]"
}
SCOPES=$(scope_mappings)
[ "${SCOPES}" != "[]" ] || echo ">> warning: no openid/email/profile scope mappings found; tokens may lack claims"

# --- OAuth2/OIDC provider ------------------------------------------------------------------------
PROVIDER_ID=$(api "${AUTHENTIK_URL}/api/v3/providers/oauth2/?name=${APP_NAME}-provider" \
  | jq -r '.results[0].pk // empty')

PROVIDER_PAYLOAD=$(jq -n \
  --arg name "${APP_NAME}-provider" \
  --arg client_id "${CLIENT_ID}" \
  --arg redirect "${CONSOLE_URL}" \
  --argjson flow "${AUTH_FLOW}" \
  --argjson key "${SIGNING_KEY}" \
  --argjson scopes "${SCOPES}" \
  '{
     name: $name,
     authorization_flow: $flow,
     client_type: "public",
     client_id: $client_id,
     redirect_uris: [{matching_mode: "regex", url: ($redirect + "(/.*)?")}],
     sub_mode: "user_id",
     issuer_mode: "per_provider",
     signing_key: $key,
     property_mappings: $scopes
   }')

if [ -z "${PROVIDER_ID}" ]; then
  echo ">> creating OAuth2 provider '${APP_NAME}-provider'"
  PROVIDER_ID=$(api -X POST -H "${CONTENT_JSON}" -d "${PROVIDER_PAYLOAD}" \
    "${AUTHENTIK_URL}/api/v3/providers/oauth2/" | jq -r '.pk')
else
  echo ">> updating OAuth2 provider '${APP_NAME}-provider'"
  api -X PUT -H "${CONTENT_JSON}" -d "${PROVIDER_PAYLOAD}" \
    "${AUTHENTIK_URL}/api/v3/providers/oauth2/${PROVIDER_ID}/" >/dev/null
fi

# Older Authentik releases take redirect_uris as a newline-separated STRING rather than a list of objects.
# If the create/update above was rejected for that reason, the provider is unusable, so say which shape failed
# rather than leaving a half-configured instance behind.
[ -n "${PROVIDER_ID}" ] && [ "${PROVIDER_ID}" != "null" ] \
  || { echo "!! could not create the provider — if this Authentik predates 2024.10, redirect_uris must be a string"; exit 1; }

# --- application ---------------------------------------------------------------------------------
APP_UUID=$(api "${AUTHENTIK_URL}/api/v3/core/applications/?slug=${APP_NAME}" | jq -r '.results[0].pk // empty')

APP_PAYLOAD=$(jq -n \
  --arg slug "${APP_NAME}" \
  --arg launch "${CONSOLE_URL}" \
  --argjson provider "${PROVIDER_ID}" \
  '{name: "HiveKeeper", slug: $slug, provider: $provider, meta_launch_url: $launch, open_in_new_tab: true}')

if [ -z "${APP_UUID}" ]; then
  echo ">> creating application '${APP_NAME}' for ${CONSOLE_URL}"
  api -X POST -H "${CONTENT_JSON}" -d "${APP_PAYLOAD}" \
    "${AUTHENTIK_URL}/api/v3/core/applications/" >/dev/null
else
  echo ">> updating application '${APP_NAME}' for ${CONSOLE_URL}"
  api -X PUT -H "${CONTENT_JSON}" -d "${APP_PAYLOAD}" \
    "${AUTHENTIK_URL}/api/v3/core/applications/${APP_UUID}/" >/dev/null
fi

# --- recovery flow on the brand ------------------------------------------------------------------
# Adding a teammate mints a one-time recovery link so the admin never knows their password; that API needs a
# recovery flow bound to the brand. Only set it when it is missing, so an operator's own choice is kept.
BRAND=$(api "${AUTHENTIK_URL}/api/v3/core/brands/?ordering=domain" | jq -r '.results[0]')
BRAND_UUID=$(echo "${BRAND}" | jq -r '.brand_uuid // empty')
BRAND_RECOVERY=$(echo "${BRAND}" | jq -r '.flow_recovery // empty')

if [ -z "${BRAND_UUID}" ]; then
  echo ">> warning: no brand found; set a recovery flow by hand or adding teammates will fail"
elif [ -n "${BRAND_RECOVERY}" ]; then
  echo ">> brand already has a recovery flow"
else
  RECOVERY_FLOW=$(api "${AUTHENTIK_URL}/api/v3/flows/instances/?slug=default-recovery-flow" \
    | jq -r '.results[0].pk // empty')
  if [ -z "${RECOVERY_FLOW}" ]; then
    echo ">> warning: no default-recovery-flow to bind; adding teammates will fail until one exists"
  else
    echo ">> binding default-recovery-flow to the brand"
    api -X PATCH -H "${CONTENT_JSON}" -d "$(jq -n --arg f "${RECOVERY_FLOW}" '{flow_recovery: $f}')" \
      "${AUTHENTIK_URL}/api/v3/core/brands/${BRAND_UUID}/" >/dev/null
  fi
fi

echo ">> Authentik configuration complete"
echo ">> OAuth2/OIDC Issuer: ${AUTHENTIK_URL}/application/o/${APP_NAME}/"
echo ">> JWKS URI: ${AUTHENTIK_URL}/application/o/${APP_NAME}/jwks/"
echo ">> Client ID: ${CLIENT_ID}"
