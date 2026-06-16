# Runs HiveKeeper in SOLO mode: a single-user, single-AP local stack. No organizations, no sign-in, no
# database, no cloud. Toggled by a single env var (HIVEKEEPER_SOLO=true) on the gateway.
#
#   powershell -ExecutionPolicy Bypass -File scripts/run-solo.ps1
#
# Starts (plain, no TLS, localhost only):
#   hive-gateway :8090   (HIVEKEEPER_SOLO=true -> no auth; every request is the local owner)
#   hive-agent   -> gateway (enrolled as 'local-agent'; SSHes your AP with the default credentials)
#   vite         :5173   (the web UI, proxying /gw->8090)
#
# Then open http://localhost:5173 (no sign-in), go to Agents -> Discover your AP -> Adopt, and manage it under
# Devices. Press Enter in this window to stop everything.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

# Your AP's SSH credentials (public Aerohive defaults). Override via env before running.
if (-not $env:HIVEKEEPER_DEFAULT_USER) { $env:HIVEKEEPER_DEFAULT_USER = 'admin' }
if (-not $env:HIVEKEEPER_DEFAULT_PASSWORD) { $env:HIVEKEEPER_DEFAULT_PASSWORD = 'aerohive' }

Write-Host 'Building distributions + web deps...' -ForegroundColor Cyan
& .\gradlew.bat :hive-gateway:installDist :hive-agent:installDist -q
if (-not (Test-Path 'hive-web\node_modules')) { Push-Location hive-web; pnpm install; Pop-Location }

Write-Host 'Starting the solo gateway (no auth, single local owner)...' -ForegroundColor Cyan
# No Spring profile: the default in-memory store + the solo flag seed one 'local' tenant and one 'local-agent'.
$env:SPRING_PROFILES_ACTIVE = ''
$env:HIVEKEEPER_SOLO = 'true'
Start-Process -FilePath '.\hive-gateway\build\install\hive-gateway\bin\hive-gateway.bat' -WindowStyle Minimized | Out-Null

Write-Host 'Starting the local agent...' -ForegroundColor Cyan
$env:HIVEKEEPER_GATEWAY_URL = 'ws://127.0.0.1:8090/agent?token=enroll-local'
$env:HIVEKEEPER_AGENT_ID = 'local-agent'
Start-Process -FilePath '.\hive-agent\build\install\hive-agent\bin\hive-agent.bat' -WindowStyle Minimized | Out-Null

Write-Host 'Starting the web UI (vite)...' -ForegroundColor Cyan
Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'cd /d hive-web ^&^& pnpm dev' -WindowStyle Minimized | Out-Null

Start-Sleep -Seconds 6
Start-Process 'http://localhost:5173'
Write-Host ''
Write-Host 'HiveKeeper SOLO is running:  http://localhost:5173' -ForegroundColor Green
Write-Host '  No sign-in. Agents -> Discover your AP -> Adopt, then open it under Devices.'
Read-Host 'Press Enter to stop everything'

Write-Host 'Stopping...' -ForegroundColor Cyan
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'hive-gateway|hive-agent' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
  Where-Object { $_.CommandLine -match 'vite' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Write-Host 'Stopped.' -ForegroundColor Green
