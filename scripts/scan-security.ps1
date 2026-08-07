# Runs HiveKeeper's Trivy checks locally — the same ones CI runs, with the same verdict.
#
#   powershell -ExecutionPolicy Bypass -File scripts/scan-security.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/scan-security.ps1 -Images   # also build + scan the images
#
# Why bother when CI already does it: finding out that a dependency bump introduces a CRITICAL takes about
# twenty seconds here and about eight minutes in a pull request. The point of a gate is that you can run it
# before you hit it.
#
# Policy is NOT duplicated in this file. Severity, ignore-unfixed, exit code and the exception list all come
# from ../trivy.yaml, which is also what the workflows pass to trivy-action, so "green locally, red in CI"
# can only mean the database moved — never that the two disagree about the rules.
[CmdletBinding()]
param(
    # Build each Dockerfile and scan the result. Off by default: it is four container builds, and the repo
    # scan is the part that changes when you touch a lockfile.
    [switch]$Images
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

if (-not (Get-Command trivy -ErrorAction SilentlyContinue)) {
    Write-Host 'trivy is not installed.' -ForegroundColor Red
    Write-Host '  winget install AquaSecurity.Trivy    (or: brew install trivy / apt install trivy)'
    exit 127
}

# Tracked rather than short-circuited: a failing repo scan must not skip the image scan, or you fix one
# thing, re-run, and discover the next one. Both run, then the script reports everything at once.
$failed = @()

Write-Host ''
Write-Host '== Repo scan: dependencies, secrets, Dockerfiles ==' -ForegroundColor Cyan
# The pnpm trees, anything secret-shaped that got committed, and the Dockerfiles as text. The JVM dependency
# tree is deliberately absent here — Trivy cannot read build.gradle.kts, so it is covered by -Images instead.
trivy --config trivy.yaml fs --scanners vuln,secret,misconfig .
if ($LASTEXITCODE -ne 0) { $failed += 'repo' }

if ($Images) {
    # Podman works unchanged: trivy talks to whichever engine socket is up, and both CLIs take these flags.
    #
    # -CommandType Application, and invoked below by full path, both on purpose. A shell profile is free to
    # alias `docker` to something else — this developer's aliases it to podman — and a bare `Get-Command
    # docker` happily returns that alias. The script would then "find" an engine and fail several minutes
    # later with a confusing "podman is not recognized" instead of the clear message below. Only a real
    # executable counts, and it is called by path so no alias can intercept it.
    $engine = @('docker', 'podman') |
        ForEach-Object { Get-Command $_ -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1 } |
        Select-Object -First 1
    if (-not $engine) {
        Write-Host 'No docker or podman executable on PATH — cannot build the images to scan them.' -ForegroundColor Red
        exit 127
    }
    Write-Host "Using container engine: $($engine.Source)" -ForegroundColor DarkGray

    foreach ($module in 'gateway', 'agent', 'server', 'web') {
        Write-Host ''
        Write-Host "== Image scan: hive-$module (base OS packages + the JVM dependency tree) ==" -ForegroundColor Cyan
        # From the repo ROOT, as every Dockerfile's header says: the builds need the whole source tree, and
        # hive-web additionally needs docs/ for the in-app help.
        & $engine.Source build -f "hive-$module/Dockerfile" -t "hivekeeper-${module}:scan" .
        if ($LASTEXITCODE -ne 0) { $failed += "build:$module"; continue }

        trivy --config trivy.yaml image "hivekeeper-${module}:scan"
        if ($LASTEXITCODE -ne 0) { $failed += "image:$module" }
    }
}

Write-Host ''
if ($failed.Count -eq 0) {
    Write-Host 'Clean.' -ForegroundColor Green
    if (-not $Images) { Write-Host 'Images were not scanned; re-run with -Images before cutting a release.' -ForegroundColor DarkGray }
    exit 0
}

Write-Host "Findings in: $($failed -join ', ')" -ForegroundColor Red
Write-Host 'Fix them, or — if a finding genuinely cannot be acted on — add it to .trivyignore.yaml WITH a'
Write-Host 'reason and an expiry date. An exception without a date is how a scanner stops meaning anything.'
exit 1
