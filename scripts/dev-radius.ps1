# Starts a co-located FreeRADIUS for HiveKeeper's PPSK-via-RADIUS runtime ("Caminho B"), in podman, on the
# standard RADIUS ports (1812/1813 UDP). The agent OWNS the user data: it writes a files-module authorize file
# (username -> Cleartext-Password + VLAN reply attrs) that this container reads. HiveKeeper never edits raddb
# by hand: the agent's FilePpskUserStore + FreeRadiusFilesProvisioner regenerate the authorize file on every
# create/rotate/revoke.
#
#   powershell -File scripts/dev-radius.ps1 [-RadiusDir <dir>] [-Secret <shared-secret>]
#   powershell -File scripts/dev-radius.ps1 -Reload      # re-sync the authorize file after the agent rewrote it
#   then run the agent with:
#     $env:HIVEKEEPER_PPSK_STORE = "<dir>\ppsk.properties"
#     $env:HIVEKEEPER_RADIUS_DIR = "<dir>"        # where the agent writes the 'authorize' file
#
# IMPLEMENTATION NOTES (learned live):
#  - The official image's daemon is /usr/sbin/freeradius (Debian naming), NOT 'radiusd'.
#  - FreeRADIUS refuses to start on a globally-writable config, and Windows bind-mounts map to 0777, so we
#    'podman cp' the config in and chmod o-w it in the entrypoint instead of bind-mounting (which fails on the
#    Windows dev box). On Linux you may bind-mount $RadiusDir directly; then the agent's writes flow live and
#    you only need 'podman kill -s HUP hivekeeper-radius' to reload. On Windows, re-run with -Reload after a
#    mutation (it re-copies the authorize file and HUPs the server). Docker users: replace 'podman' with 'docker'.
#
# This is a LAB helper. See docs/ppsk-radius-runbook.md for the end-to-end validation (pointing a throwaway AP
# security-object at this server and connecting a real client).
param(
  [string]$RadiusDir = (Join-Path $env:TEMP 'hivekeeper-radius'),
  [string]$Secret = 'testing123',
  [switch]$Reload
)
$ErrorActionPreference = 'Stop'
$image = 'docker.io/freeradius/freeradius-server:latest'
$authorize = Join-Path $RadiusDir 'authorize'
$authPath = '/etc/raddb/mods-config/files/authorize'

if ($Reload) {
  if (-not (Test-Path $authorize)) { throw "no authorize file at $authorize" }
  podman cp "$authorize" "hivekeeper-radius:$authPath"
  podman exec hivekeeper-radius chmod o-w $authPath
  podman kill -s HUP hivekeeper-radius | Out-Null
  Write-Host "reloaded FreeRADIUS from $authorize" -ForegroundColor Green
  return
}

$null = New-Item -ItemType Directory -Force -Path $RadiusDir
# Write files WITHOUT a BOM: PowerShell 5.1's `Set-Content -Encoding utf8` prepends a UTF-8 BOM, and FreeRADIUS
# fails to parse a config/authorize file that starts with one. (The agent writes BOM-free already.)
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
if (-not (Test-Path $authorize)) {
  # Seed an empty authorize file so FreeRADIUS starts cleanly before the agent has provisioned anyone.
  [System.IO.File]::WriteAllText($authorize, "# managed by hive-agent - generated`n", $utf8NoBom)
}
# Point the AP at this server with the SAME shared secret. clients.conf lets the AP's mgt0 subnet talk to us.
$clients = Join-Path $RadiusDir 'clients.conf'
$clientsConf = @(
  'client lan {',
  '    ipaddr = 0.0.0.0/0',
  "    secret = $Secret",
  '}'
) -join "`n"
[System.IO.File]::WriteAllText($clients, $clientsConf + "`n", $utf8NoBom)

podman rm -f hivekeeper-radius 2>$null | Out-Null
# Create (not run) so we can cp the config in before first start; the entrypoint strips other-write (FreeRADIUS
# refuses globally-writable config) and execs the daemon in the foreground.
$entry = "chmod o-w /etc/raddb/clients.conf $authPath; exec /usr/sbin/freeradius -f -l stdout"
podman create --name hivekeeper-radius -p 1812:1812/udp -p 1813:1813/udp --entrypoint /bin/sh $image -c $entry | Out-Null
podman cp "$authorize" "hivekeeper-radius:$authPath"
podman cp "$clients" "hivekeeper-radius:/etc/raddb/clients.conf"
podman start hivekeeper-radius | Out-Null

Write-Host "FreeRADIUS started on :1812/udp (shared-secret '$Secret')" -ForegroundColor Cyan
Write-Host "authorize file: $authorize" -ForegroundColor Green
Write-Host "Run the agent with:" -ForegroundColor Green
Write-Host "  `$env:HIVEKEEPER_PPSK_STORE = '$RadiusDir\ppsk.properties'" -ForegroundColor Green
Write-Host "  `$env:HIVEKEEPER_RADIUS_DIR = '$RadiusDir'" -ForegroundColor Green
Write-Host "Validate:  podman exec hivekeeper-radius radtest <user> <psk> 127.0.0.1 0 $Secret" -ForegroundColor Yellow
Write-Host "Logs:      podman logs -f hivekeeper-radius" -ForegroundColor Yellow
Write-Host "After the agent rewrites authorize:  powershell -File scripts/dev-radius.ps1 -Reload" -ForegroundColor Yellow
