# Starts a dedicated Postgres for the HiveKeeper gateway (podman) on :5433 and creates the RESTRICTED
# application role (NOSUPERUSER, NOBYPASSRLS) so Row-Level Security is genuinely enforced.
#
#   powershell -File scripts/dev-postgres.ps1
#   then run the gateway with:  SPRING_PROFILES_ACTIVE=postgres
#
# Flyway (running as the 'postgres' admin) creates the schema, seed, RLS policies, and grants on first
# gateway start. Docker users: replace 'podman' with 'docker'.
$ErrorActionPreference = 'Stop'

podman rm -f hivekeeper-pg 2>$null | Out-Null
podman run -d --name hivekeeper-pg `
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=hivekeeper `
  -p 5433:5432 docker.io/library/postgres:16-alpine | Out-Null

Write-Host 'Waiting for Postgres...' -ForegroundColor Cyan
$ready = $false
foreach ($i in 1..40) {
  if ((podman exec hivekeeper-pg pg_isready -U postgres -d hivekeeper 2>&1) -match 'accepting connections') { $ready = $true; break }
  Start-Sleep -Milliseconds 750
}
if (-not $ready) { throw 'Postgres did not become ready' }

podman exec hivekeeper-pg psql -U postgres -d hivekeeper -v ON_ERROR_STOP=1 -c @'
CREATE ROLE hivekeeper_app LOGIN PASSWORD 'app' NOSUPERUSER NOBYPASSRLS;
GRANT CONNECT ON DATABASE hivekeeper TO hivekeeper_app;
GRANT USAGE ON SCHEMA public TO hivekeeper_app;
'@ | Out-Null

Write-Host 'Ready: localhost:5433  db=hivekeeper  app=hivekeeper_app/app  admin=postgres/postgres' -ForegroundColor Green
Write-Host 'Start the gateway with:  $env:SPRING_PROFILES_ACTIVE = "postgres"' -ForegroundColor Green
