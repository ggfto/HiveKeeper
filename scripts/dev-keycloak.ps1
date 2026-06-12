# Starts a dev Keycloak (podman) on :8081 and imports the 'hivekeeper' realm: a public 'hive-gateway'
# client (standard + direct-access grants) and three users (owner/op/view, password = username) whose ids
# match the seeded app_user rows. Run the gateway with SPRING_PROFILES_ACTIVE=postgres,oidc to validate the
# tokens it issues. Docker users: replace 'podman' with 'docker'.
#
#   powershell -File scripts/dev-keycloak.ps1
$ErrorActionPreference = 'Stop'
$realm = Join-Path $PSScriptRoot 'hivekeeper-realm.json'

podman rm -f hivekeeper-kc 2>$null | Out-Null

# Stage the realm in a temp dir, then copy that DIR onto the (non-existent) import path: podman cp creates
# the destination dir from the source's contents. This avoids a Windows->podman bind mount and lets the
# --import-realm run honor the fixed user ids.
$stage = Join-Path $env:TEMP 'hk-kc-import'
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
$null = New-Item -ItemType Directory -Force -Path $stage
Copy-Item $realm (Join-Path $stage 'hivekeeper-realm.json') -Force

podman create --name hivekeeper-kc -p 8081:8080 `
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin `
  quay.io/keycloak/keycloak:26.0 start-dev --import-realm | Out-Null
podman cp "$stage" hivekeeper-kc:/opt/keycloak/data/import
podman start hivekeeper-kc | Out-Null

Write-Host 'Keycloak starting on http://localhost:8081  (admin console: admin/admin)' -ForegroundColor Cyan
Write-Host 'Realm: hivekeeper   client: hive-gateway   users: owner/owner, op/op, view/view' -ForegroundColor Green
Write-Host 'Then run the gateway with:  $env:SPRING_PROFILES_ACTIVE = "postgres,oidc"' -ForegroundColor Green
