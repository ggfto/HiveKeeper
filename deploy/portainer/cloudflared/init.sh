#!/bin/sh
# Provisions a LOCALLY-MANAGED Cloudflare Tunnel through the API — no `cloudflared tunnel login`, no
# cert.pem, nothing to copy onto the host by hand. Runs as an init container; the cloudflared runner starts
# after it and reads what this wrote from the shared /etc/cloudflared volume.
#
# Idempotent by design, because it runs on every `up`: it creates the tunnel only if it is missing, and
# upserts each DNS record rather than assuming it is absent.
#
# The ingress is RENDERED FROM THE ENVIRONMENT rather than read from a versioned file. That is the whole
# point of this deployment: the hostnames are stack variables in Portainer, so changing where HiveKeeper
# answers is an env edit and a redeploy, not a commit.
set -eu

: "${CF_API_TOKEN:?set CF_API_TOKEN (scopes: Account > Cloudflare Tunnel: Edit + Zone > DNS: Edit)}"
: "${CF_ACCOUNT_ID:?set CF_ACCOUNT_ID}"
: "${CF_ZONE_ID:?set CF_ZONE_ID}"
: "${TUNNEL_NAME:?set TUNNEL_NAME — one tunnel per stack, so it must be unique in the account}"
: "${HIVEKEEPER_DOMAIN:?set HIVEKEEPER_DOMAIN — the console hostname, e.g. hive.example.org}"
: "${HIVEKEEPER_AGENT_DOMAIN:?set HIVEKEEPER_AGENT_DOMAIN — the agent hostname, e.g. agents.example.org}"

OUT=/etc/cloudflared
CRED="$OUT/creds.json"
CFG="$OUT/config.yml"
INGRESS="$OUT/ingress.yml"
API="https://api.cloudflare.com/client/v4"
ACCT="$API/accounts/$CF_ACCOUNT_ID"

# cf METHOD URL [JSON_BODY]
cf() {
  if [ -n "${3:-}" ]; then
    curl -fsS -X "$1" "$2" \
      -H "Authorization: Bearer $CF_API_TOKEN" -H "Content-Type: application/json" --data "$3"
  else
    curl -fsS -X "$1" "$2" -H "Authorization: Bearer $CF_API_TOKEN"
  fi
}

mkdir -p "$OUT"

# --- the ingress ---------------------------------------------------------------------------------------
# Rule order is load-bearing. Both /auth and the console live on the same hostname, and cloudflared takes
# the FIRST rule that matches — so the `path` rule has to come before the catch-all for that host, or
# Keycloak is never reached and every login 404s inside the SPA.
#
# `path` is a regular expression against the request path, and it is NOT implicitly anchored at the end.
# `^/auth` alone would also capture /authentic, /authorize, /auth-settings — any console route that merely
# starts with those five characters would be handed to Keycloak, which would 404 it. `^/auth(/.*)?$` matches
# /auth and everything under it, and nothing else.
#
# Keycloak is *serving* at /auth (KC_HTTP_RELATIVE_PATH), so the prefix must be passed through, not
# stripped — the same reason the Caddyfile used `handle` rather than `handle_path`.
#
# The agent hostname is `tcp://`, not http. Agents authenticate with a TLS CLIENT CERTIFICATE that the
# gateway reads off the connection itself; an HTTP ingress terminates TLS at the edge and would swallow it,
# failing every handshake and every certificate renewal — closed, and silently. A tcp: ingress is a raw
# byte pipe, so the TLS session runs end to end from the agent to the gateway with the certificate intact.
# The other end of that pipe is a `cloudflared access tcp` sidecar next to the agent (agent-compose.yml).
cat > "$INGRESS" <<EOF
ingress:
  - hostname: $HIVEKEEPER_DOMAIN
    path: ^/auth(/.*)?$
    service: http://keycloak:8080

  - hostname: $HIVEKEEPER_DOMAIN
    service: http://web:8080

  - hostname: $HIVEKEEPER_AGENT_DOMAIN
    service: tcp://gateway:9443

  # Mandatory catch-all, always last.
  - service: http_status:404
EOF

echo "==> [$TUNNEL_NAME] looking for the tunnel..."
TID=$(cf GET "$ACCT/cfd_tunnel?name=$TUNNEL_NAME&is_deleted=false" | jq -r '.result[0].id // empty')
if [ -z "$TID" ]; then
  echo "==> not found; creating it (config_src=local)..."
  TID=$(cf POST "$ACCT/cfd_tunnel" "{\"name\":\"$TUNNEL_NAME\",\"config_src\":\"local\"}" | jq -r '.result.id')
fi
[ -n "$TID" ] || { echo "ERROR: the API did not return a tunnel id"; exit 1; }
echo "==> tunnel id: $TID"

# The credentials are DERIVED from the tunnel token rather than stored: the token is a base64 JSON blob
# whose fields are exactly what creds.json needs. So this volume can be thrown away and rebuilt from the
# API on the next boot, and the only durable secret is CF_API_TOKEN.
echo "==> fetching the token and writing creds.json..."
TOKEN=$(cf GET "$ACCT/cfd_tunnel/$TID/token" | jq -r '.result')
echo "$TOKEN" | base64 -d | jq '{AccountTag: .a, TunnelID: .t, TunnelSecret: .s}' > "$CRED"

echo "==> assembling config.yml..."
{
  echo "tunnel: $TID"
  echo "credentials-file: $CRED"
  cat "$INGRESS"
} > "$CFG"

# --- DNS -----------------------------------------------------------------------------------------------
# Every hostname in the ingress becomes a proxied CNAME to the tunnel. `sort -u` because the console
# hostname appears twice in the ingress (the /auth rule and the catch-all) and there is no reason to make
# the same API call twice.
echo "==> ensuring CNAMEs (-> $TID.cfargotunnel.com)..."
CONTENT="$TID.cfargotunnel.com"
DNS_FAIL=0
for H in $(grep -oE 'hostname:[[:space:]]*[^[:space:]]+' "$INGRESS" | awk '{print $2}' | sort -u); do
  RESP=$(cf GET "$API/zones/$CF_ZONE_ID/dns_records?type=CNAME&name=$H" 2>/dev/null) \
    || { echo "    ! $H: DNS lookup failed (token missing Zone:DNS:Edit? is CF_ZONE_ID right?)"; DNS_FAIL=1; continue; }
  RID=$(echo "$RESP" | jq -r '.result[0].id // empty')
  BODY="{\"type\":\"CNAME\",\"name\":\"$H\",\"content\":\"$CONTENT\",\"proxied\":true}"
  if [ -z "$RID" ]; then
    cf POST "$API/zones/$CF_ZONE_ID/dns_records" "$BODY" >/dev/null 2>&1 \
      && echo "    + $H" || { echo "    ! $H: creating the CNAME failed (403 = token missing DNS:Edit)"; DNS_FAIL=1; }
  else
    cf PUT "$API/zones/$CF_ZONE_ID/dns_records/$RID" "$BODY" >/dev/null 2>&1 \
      && echo "    ~ $H" || { echo "    ! $H: updating the CNAME failed (403 = token missing DNS:Edit)"; DNS_FAIL=1; }
  fi
done

# Fail the init container rather than let the runner come up half-routed: a tunnel whose DNS never
# resolved looks identical to a tunnel that is simply down, and you would debug the wrong half.
if [ "$DNS_FAIL" != 0 ]; then
  echo "ERROR: CNAMEs were not configured. Give CF_API_TOKEN 'Zone > DNS: Edit' on this zone and redeploy."
  exit 1
fi

echo "==> ready: config.yml and creds.json in $OUT (tunnel $TUNNEL_NAME / $TID)."
