# Runs the whole HiveKeeper stack on localhost and opens the web UI.
#
#   powershell -ExecutionPolicy Bypass -File scripts/run-local.ps1
#
# Starts (plain, no TLS — this is the local dev experience):
#   hive-server  :8080   (mode B: direct SSH)
#   hive-gateway :8090   (mode C: control plane)
#   hive-agent   -> gateway (enrolled to tenant 'acme', creds for the lab AP)
#   vite         :5173   (the web UI, proxying /api->8080 and /gw->8090)
#
# Then open http://localhost:5173 . Press Enter in this window to stop everything.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

# The lab AP credentials the agent uses (public Aerohive defaults). Override via env before running.
if (-not $env:HIVEKEEPER_DEFAULT_USER) { $env:HIVEKEEPER_DEFAULT_USER = 'admin' }
if (-not $env:HIVEKEEPER_DEFAULT_PASSWORD) { $env:HIVEKEEPER_DEFAULT_PASSWORD = 'aerohive' }

Write-Host 'Building distributions + web deps...' -ForegroundColor Cyan
& .\gradlew.bat :hive-server:installDist :hive-gateway:installDist :hive-agent:installDist -q
if (-not (Test-Path 'hive-web\node_modules')) { Push-Location hive-web; pnpm install; Pop-Location }

Write-Host 'Starting backends...' -ForegroundColor Cyan
Start-Process -FilePath '.\hive-server\build\install\hive-server\bin\hive-server.bat' -WindowStyle Minimized | Out-Null
Start-Process -FilePath '.\hive-gateway\build\install\hive-gateway\bin\hive-gateway.bat' -WindowStyle Minimized | Out-Null

$env:HIVEKEEPER_GATEWAY_URL = 'ws://127.0.0.1:8090/agent?token=enroll-lab-agent'
$env:HIVEKEEPER_AGENT_ID = 'lab-agent'
Start-Process -FilePath '.\hive-agent\build\install\hive-agent\bin\hive-agent.bat' -WindowStyle Minimized | Out-Null

Write-Host 'Starting web UI (vite)...' -ForegroundColor Cyan
Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'cd /d hive-web ^&^& pnpm dev' -WindowStyle Minimized | Out-Null

Start-Sleep -Seconds 6
Start-Process 'http://localhost:5173'
Write-Host ''
Write-Host 'HiveKeeper is running:  http://localhost:5173' -ForegroundColor Green
Write-Host '  - Direct mode:  enter 192.168.1.101 -> Discover / Inventory / Backup'
Write-Host '  - Gateway mode: tenant key "acme-key" -> pick lab-agent -> inventory the AP'
Read-Host 'Press Enter to stop everything'

Write-Host 'Stopping...' -ForegroundColor Cyan
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'hive-server|hive-gateway|hive-agent' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
  Where-Object { $_.CommandLine -match 'vite' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Write-Host 'Stopped.' -ForegroundColor Green
