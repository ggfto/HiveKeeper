#!/usr/bin/env bash
# Generates the private PKI that agents authenticate with, into the `pki` volume, ONCE.
#
# This is the in-stack equivalent of scripts/init-secrets.sh's keytool half. It exists because a Portainer
# Git stack cannot bind-mount ./secrets/pki from the repo checkout — there is nowhere to put the files by
# hand, so the stack has to mint them itself on first boot.
#
# It produces:
#   ca.p12          the CA. Its private key signs every agent certificate — it IS the fleet's trust.
#   truststore.p12  the CA alone. What makes an agent's certificate verifiable, on both sides.
#   gateway.p12     the gateway's server certificate for the agent-facing TLS port (9443).
#   ca.pem          the CA certificate. Public, not a secret — each agent needs it to trust the gateway
#                   during bootstrap, before it has a truststore of its own.
set -euo pipefail

PKI_DIR="${PKI_DIR:-/pki}"
: "${HIVEKEEPER_AGENT_DOMAIN:?set HIVEKEEPER_AGENT_DOMAIN — the hostname agents dial, e.g. agents.example.org}"
: "${HIVEKEEPER_PKI_STORE_PASSWORD:?set HIVEKEEPER_PKI_STORE_PASSWORD — the same value the gateway gets}"

P="$HIVEKEEPER_PKI_STORE_PASSWORD"

# Emitting ca.pem on stdout is how the operator gets it out of the volume: read it from this container's
# log in Portainer, no shell on the host required. It runs on the already-generated path too, so the
# certificate stays retrievable long after the first boot — you will need it again for the next agent.
emit_ca_pem() {
  echo
  echo "===== BEGIN ca.pem (copy this to the agent machine) ====="
  cat "$PKI_DIR/ca.pem"
  echo "===== END ca.pem ====="
  echo
}

# REFUSE to regenerate. Minting a new CA orphans every agent already enrolled against the old one — they
# would all fail their handshake at once, and the only fix is re-enrolling the entire fleet by hand.
# scripts/init-secrets.sh refuses loudly for the same reason; here it has to succeed quietly, because this
# container runs on every single `up`.
if [ -f "$PKI_DIR/ca.p12" ]; then
  echo "==> PKI already present in $PKI_DIR — leaving it alone (regenerating the CA would orphan every enrolled agent)."
  emit_ca_pem
  exit 0
fi

echo "==> generating the private PKI (CA + gateway server certificate for ${HIVEKEEPER_AGENT_DOMAIN})"
cd "$PKI_DIR"

# The CA.
keytool -genkeypair -alias ca -keyalg RSA -keysize 4096 -validity 3650 \
  -dname 'CN=HiveKeeper CA,O=HiveKeeper' -ext bc:c \
  -keystore ca.p12 -storetype PKCS12 -storepass "$P" -keypass "$P"
keytool -exportcert -alias ca -rfc -file ca.pem -keystore ca.p12 -storepass "$P"

# The truststore is the CA alone.
keytool -importcert -noprompt -alias ca -file ca.pem \
  -keystore truststore.p12 -storetype PKCS12 -storepass "$P"

# The gateway's SERVER certificate. Its SAN must contain the host the agents dial or they will refuse the
# connection — correctly. That host is HIVEKEEPER_AGENT_DOMAIN, which resolves to the `cloudflared access
# tcp` sidecar on the agent's machine; the TLS session itself still terminates at the gateway, so the name
# on this certificate is what the agent verifies.
keytool -genkeypair -alias gateway -keyalg RSA -keysize 2048 -validity 825 \
  -dname "CN=${HIVEKEEPER_AGENT_DOMAIN},O=HiveKeeper" -ext "san=dns:${HIVEKEEPER_AGENT_DOMAIN}" \
  -keystore gateway.p12 -storetype PKCS12 -storepass "$P" -keypass "$P"
keytool -certreq -alias gateway -file gateway.csr -keystore gateway.p12 -storepass "$P"
keytool -gencert -alias ca -ext "san=dns:${HIVEKEEPER_AGENT_DOMAIN}" -validity 825 -rfc \
  -infile gateway.csr -outfile gateway.crt -keystore ca.p12 -storepass "$P"
keytool -importcert -noprompt -alias ca -file ca.pem -keystore gateway.p12 -storepass "$P"
keytool -importcert -noprompt -alias gateway -file gateway.crt -keystore gateway.p12 -storepass "$P"

rm -f gateway.csr gateway.crt

# The gateway runs as a different uid and mounts this read-only; it only ever needs to read.
chmod 0644 ./*.p12 ca.pem

echo "==> PKI written to $PKI_DIR"
emit_ca_pem

cat <<'EOF'
BACK UP THIS VOLUME. ca.p12 signs every agent certificate: lose it and the whole fleet must re-enroll.
Losing HIVEKEEPER_PKI_STORE_PASSWORD has the same effect — the keystores become unopenable.
EOF
