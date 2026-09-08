#!/usr/bin/env bash
# Configures an Authentik instance for HiveKeeper: the OAuth2/OIDC provider, the application, and the brand's
# recovery flow.
#
# Idempotent: safe to re-run on every stack start. It creates what is missing and updates what has drifted.
#
# Needs bash, curl and jq. Runs as the `authentik-init` service in docker-compose.authentik.yml, which
# installs them into a plain alpine image. It also runs standalone against an Authentik you already operate:
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
AUTH_FLOW=$(api "${AUTHENTIK_URL}/api/v3/flows/instances/?designation=authentication" \
  | jq -r '[.results[] | select(.slug == "default-authentication-flow")][0].pk // empty')
[ -n "${AUTH_FLOW}" ] || { echo "!! no default-authentication-flow in this Authentik"; exit 1; }

# Required on providers from Authentik 2025.x; the field did not exist before it, and older servers ignore
# it. Prefer the provider-specific flow when one is present, since that is what a logout from THIS
# application should run.
INVALIDATION_FLOW=$(api "${AUTHENTIK_URL}/api/v3/flows/instances/?designation=invalidation&ordering=slug" \
  | jq -r '[.results[] | select(.slug == "default-provider-invalidation-flow")][0].pk // .results[0].pk // empty')

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
# Authentik changed the shape of `redirect_uris` in 2024.10: it used to be a newline-separated STRING of
# regexes, and became a list of {matching_mode, url} objects. Both are in the field, so build both payloads
# and let the server pick — the older API rejects the list with 400 "Not a valid string.".
PROVIDER_ID=$(api "${AUTHENTIK_URL}/api/v3/providers/oauth2/?name=${APP_NAME}-provider"   | jq -r '.results[0].pk // empty')

# The console posts back to exactly one URI: `window.location.origin + "/"` — see
# hive-web/src/lib/oidcConfig.js, which uses it for BOTH redirect_uri and post_logout_redirect_uri. So the
# provider gets that one value and nothing wider.

# The browser's origin never carries a path, so drop anything the operator appended to CONSOLE_URL.
console_origin() {
  printf '%s' "$1" | sed -E 's#^([a-zA-Z][a-zA-Z0-9+.-]*://[^/]+).*#\1#'
}

# Escape regex metacharacters. Authentik matches the legacy string form of redirect_uris as a REGULAR
# EXPRESSION (re.fullmatch), so a pattern built by plain concatenation is dangerously loose: an unescaped dot
# matches any character, so https://hivekeeper.example.org would also match https://hivekeeper-example.org —
# a domain an attacker can register. Against a public client using PKCE that is enough to have the
# authorization code delivered to them. jq does the escaping because its regex and string semantics are
# well defined; sed bracket expressions are not portable enough to trust with this.
regex_escape() {
  jq -rn --arg s "$1" '$s | gsub("(?<c>[.\\[\\]^$*+?(){}|\\\\])"; "\\" + .c)'
}

REDIRECT_URI="$(console_origin "${CONSOLE_URL}")/"
REDIRECT_REGEX="^$(regex_escape "${REDIRECT_URI}")\$"

provider_payload() {   # $1: "list" (2024.10+) or "string" (older)
  local redirect
  if [ "$1" = "list" ]; then
    redirect=$(jq -n --arg u "${REDIRECT_URI}" '[{matching_mode: "strict", url: $u}]')
  else
    redirect=$(jq -n --arg u "${REDIRECT_REGEX}" '$u')
  fi
  # invalidation_flow is only sent when the server offered one: it is required from Authentik 2025.x and did
  # not exist before, so an empty string would be rejected by the very releases it is meant to support.
  jq -n \
    --arg name "${APP_NAME}-provider" \
    --arg client_id "${CLIENT_ID}" \
    --arg flow "${AUTH_FLOW}" \
    --arg invalidation "${INVALIDATION_FLOW}" \
    --arg key "${SIGNING_KEY}" \
    --argjson redirect "${redirect}" \
    --argjson scopes "${SCOPES}" \
    '{
       name: $name,
       authorization_flow: $flow,
       client_type: "public",
       client_id: $client_id,
       redirect_uris: $redirect,
       sub_mode: "user_id",
       issuer_mode: "per_provider",
       signing_key: $key,
       property_mappings: $scopes
     }
     + (if $invalidation == "" then {} else {invalidation_flow: $invalidation} end)'
}

# Write the provider with whichever shape this Authentik accepts. Echoes the pk; prints the server's own
# error and fails if BOTH shapes are rejected, rather than leaving a half-configured instance behind.
save_provider() {
  local method url shape body code
  if [ -z "${PROVIDER_ID}" ]; then
    method=POST; url="${AUTHENTIK_URL}/api/v3/providers/oauth2/"
  else
    method=PUT;  url="${AUTHENTIK_URL}/api/v3/providers/oauth2/${PROVIDER_ID}/"
  fi
  for shape in list string; do
    body=$(curl -s -w '
%{http_code}' -X "${method}" -H "${AUTH_HEADER}" -H "${CONTENT_JSON}"       -d "$(provider_payload "${shape}")" "${url}")
    code=$(echo "${body}" | tail -n1)
    body=$(echo "${body}" | sed '$d')
    if [ "${code}" = "200" ] || [ "${code}" = "201" ]; then
      echo "${body}" | jq -r '.pk'
      return 0
    fi
    # Retry the other shape ONLY when that is what was rejected. Any other 400 - a missing required field,
    # say - is not a shape problem, and blaming redirect_uris for it buries the server's real message.
    case "${body}" in
      *redirect_uris*) echo ">> this Authentik does not take redirect_uris as a ${shape} (HTTP ${code})" >&2 ;;
      *) echo "!! the provider was rejected (HTTP ${code}): ${body}" >&2; return 1 ;;
    esac
  done
  echo "!! could not save the provider: ${body}" >&2
  return 1
}

if [ -z "${PROVIDER_ID}" ]; then
  echo ">> creating OAuth2 provider '${APP_NAME}-provider'"
else
  echo ">> updating OAuth2 provider '${APP_NAME}-provider'"
fi
PROVIDER_ID=$(save_provider)
[ -n "${PROVIDER_ID}" ] && [ "${PROVIDER_ID}" != "null" ] || { echo "!! no provider pk came back"; exit 1; }

# --- application ---------------------------------------------------------------------------------
# Applications are addressed by SLUG in the REST path, not by pk — a pk there answers 404.
# The match is client-side on purpose: on Authentik 2025.x a ?slug= filter here narrows the pagination
# COUNT but not the results array, so results[0] is simply the first application in the instance. Trusting
# it made this script decide an application it had never created already existed, PUT to a slug that did
# not exist, and die on the 404.
APP_EXISTS=$(api "${AUTHENTIK_URL}/api/v3/core/applications/" \
  | jq -r --arg s "${APP_NAME}" '[.results[] | select(.slug == $s)][0].slug // empty')

APP_PAYLOAD=$(jq -n \
  --arg slug "${APP_NAME}" \
  --arg launch "${CONSOLE_URL}" \
  --argjson provider "${PROVIDER_ID}" \
  '{name: "HiveKeeper", slug: $slug, provider: $provider, meta_launch_url: $launch, open_in_new_tab: true}')

if [ -z "${APP_EXISTS}" ]; then
  echo ">> creating application '${APP_NAME}' for ${CONSOLE_URL}"
  api -X POST -H "${CONTENT_JSON}" -d "${APP_PAYLOAD}" \
    "${AUTHENTIK_URL}/api/v3/core/applications/" >/dev/null
else
  echo ">> updating application '${APP_NAME}' for ${CONSOLE_URL}"
  api -X PUT -H "${CONTENT_JSON}" -d "${APP_PAYLOAD}" \
    "${AUTHENTIK_URL}/api/v3/core/applications/${APP_NAME}/" >/dev/null
fi

# --- recovery flow on the brand ------------------------------------------------------------------
# Adding a teammate mints a one-time recovery link so the admin never learns their password, and Authentik
# refuses that call unless a recovery flow is the active brand's default.
#
# Authentik ships NO recovery flow: out of the box the only flow that touches passwords is
# `default-password-change`, whose designation is stage_configuration, not recovery. So create a minimal one
# — the same prompt + user_write stages that flow already uses, which is exactly "set a new password" — and
# bind it. The stages are reused, not duplicated, so an operator who customises them gets both paths at once.
ensure_recovery_flow() {
  local existing pk stage order
  existing=$(api "${AUTHENTIK_URL}/api/v3/flows/instances/?designation=recovery"     | jq -r '.results[0].pk // empty')
  if [ -n "${existing}" ]; then
    echo "${existing}"
    return 0
  fi

  echo ">> creating recovery flow '${RECOVERY_SLUG}'" >&2
  pk=$(api -X POST -H "${CONTENT_JSON}" "${AUTHENTIK_URL}/api/v3/flows/instances/" -d "$(jq -n         --arg slug "${RECOVERY_SLUG}"         '{name: "HiveKeeper recovery", slug: $slug, title: "Set your password",
          designation: "recovery", authentication: "none"}')"       | jq -r '.pk // empty')
  [ -n "${pk}" ] || { echo "!! could not create the recovery flow" >&2; return 1; }

  # `authentication: none` is deliberate, not laziness. Authentik 2025.x evaluates a flow's authentication
  # requirement against the session that ASKS for the link — which is the gateway's own API call,
  # authenticated as the token's user. Under require_unauthenticated the recovery endpoint answers
  # 400 "Recovery flow not applicable to user" and no link is ever issued. It worked on 2024.8.
  #
  # It is safe because the flow cannot mint an account for a passer-by: default-password-change-write ships
  # with user_creation_mode=never_create, so with no pending user the flow dead-ends. The only way in stays
  # the one-time flow_token carried by the link.
  order=0
  for stage in default-password-change-prompt default-password-change-write; do
    local stage_pk
    stage_pk=$(api "${AUTHENTIK_URL}/api/v3/stages/all/" \
      | jq -r --arg n "${stage}" '[.results[] | select(.name == $n)][0].pk // empty')
    [ -n "${stage_pk}" ] || { echo "!! stage ${stage} not found" >&2; return 1; }
    api -X POST -H "${CONTENT_JSON}" "${AUTHENTIK_URL}/api/v3/flows/bindings/"       -d "$(jq -n --arg t "${pk}" --arg s "${stage_pk}" --argjson o "${order}"             '{target: $t, stage: $s, order: $o}')" >/dev/null
    order=$((order + 1))
  done
  echo "${pk}"
}

RECOVERY_SLUG="${HIVEKEEPER_RECOVERY_SLUG:-hivekeeper-recovery}"
BRAND=$(api "${AUTHENTIK_URL}/api/v3/core/brands/?ordering=domain" | jq -r '.results[0]')
BRAND_UUID=$(echo "${BRAND}" | jq -r '.brand_uuid // empty')
BRAND_RECOVERY=$(echo "${BRAND}" | jq -r '.flow_recovery // empty')

if [ -z "${BRAND_UUID}" ]; then
  echo ">> warning: no brand found; adding teammates will fail until one has a recovery flow"
elif [ -n "${BRAND_RECOVERY}" ]; then
  echo ">> brand already has a recovery flow"
else
  # Only set it when missing, so an operator's own choice is never overwritten.
  RECOVERY_FLOW=$(ensure_recovery_flow)
  if [ -z "${RECOVERY_FLOW}" ]; then
    echo ">> warning: no recovery flow available; adding teammates will fail"
  else
    echo ">> binding recovery flow to the brand"
    api -X PATCH -H "${CONTENT_JSON}" -d "$(jq -n --arg f "${RECOVERY_FLOW}" '{flow_recovery: $f}')"       "${AUTHENTIK_URL}/api/v3/core/brands/${BRAND_UUID}/" >/dev/null
  fi
fi

echo ">> Authentik configuration complete"
echo ">> OAuth2/OIDC Issuer: ${AUTHENTIK_URL}/application/o/${APP_NAME}/"
echo ">> JWKS URI: ${AUTHENTIK_URL}/application/o/${APP_NAME}/jwks/"
echo ">> Client ID: ${CLIENT_ID}"
