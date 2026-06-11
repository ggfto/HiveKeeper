# Generates a local dev PKI for mTLS between the gateway and agents.
# Output goes to ../dev-pki (gitignored). NEVER use these certs outside local development.
#
#   powershell -File scripts/gen-dev-pki.ps1
#
# Produces:
#   dev-pki/gateway.p12    server key+cert (CN=localhost) -> gateway server.ssl.key-store
#   dev-pki/agent.p12      client key+cert (CN=lab-agent) -> agent keystore
#   dev-pki/truststore.p12 the CA only -> both sides' trust-store
$ErrorActionPreference = 'Stop'
$pass = 'changeit'
$pki = Join-Path (Split-Path $PSScriptRoot -Parent) 'dev-pki'
New-Item -ItemType Directory -Force -Path $pki | Out-Null
Get-ChildItem $pki -File -ErrorAction SilentlyContinue | Remove-Item -Force

# Certificate Authority
keytool -genkeypair -alias ca -keyalg RSA -keysize 2048 -validity 825 `
  -dname 'CN=HiveKeeper Dev CA,O=HiveKeeper' -ext 'bc:c' `
  -keystore "$pki\ca.p12" -storetype PKCS12 -storepass $pass -keypass $pass
keytool -exportcert -alias ca -rfc -file "$pki\ca.crt" -keystore "$pki\ca.p12" -storepass $pass

# Truststore = the CA cert only
keytool -importcert -noprompt -alias ca -file "$pki\ca.crt" `
  -keystore "$pki\truststore.p12" -storetype PKCS12 -storepass $pass

function New-SignedCert([string]$alias, [string]$dname, [string]$san, [string]$store) {
  keytool -genkeypair -alias $alias -keyalg RSA -keysize 2048 -validity 825 `
    -dname $dname -ext "san=$san" -keystore $store -storetype PKCS12 -storepass $pass -keypass $pass
  keytool -certreq -alias $alias -file "$pki\$alias.csr" -keystore $store -storepass $pass
  keytool -gencert -alias ca -ext "san=$san" -validity 825 -rfc `
    -infile "$pki\$alias.csr" -outfile "$pki\$alias.crt" -keystore "$pki\ca.p12" -storepass $pass
  keytool -importcert -noprompt -alias ca -file "$pki\ca.crt" -keystore $store -storepass $pass
  keytool -importcert -noprompt -alias $alias -file "$pki\$alias.crt" -keystore $store -storepass $pass
}

New-SignedCert 'gateway' 'CN=localhost,O=HiveKeeper' 'dns:localhost,ip:127.0.0.1' "$pki\gateway.p12"
New-SignedCert 'lab-agent' 'CN=lab-agent,O=HiveKeeper' 'dns:lab-agent' "$pki\agent.p12"

Write-Output "dev PKI written to $pki"
Get-ChildItem $pki -Filter *.p12 | Select-Object Name, Length
