#requires -Version 5.1
<#
.SYNOPSIS
    Checks that the JARVIS laptop agent is running and can execute a command.

.DESCRIPTION
    Runs three requests the phone would make:
      GET  /ping     no token, proves something is listening
      GET  /health   token, proves the token matches and lists commands
      POST /execute  token, runs the read-only "whoami" command

    Prints the real status of each step. Exits non-zero if any step fails, so it
    can be used in a script.

.PARAMETER BaseUrl
    Agent address. Defaults to http://127.0.0.1:<port from config.json>.

.PARAMETER Token
    Token to use. Defaults to the one in config.json.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = '',
    [string]$Token = ''
)

$ErrorActionPreference = 'Continue'
$configPath = Join-Path $PSScriptRoot 'config.json'

if (-not (Test-Path -LiteralPath $configPath)) {
    Write-Host "ERROR: $configPath does not exist. Run start-agent.ps1 first." -ForegroundColor Red
    exit 1
}

$config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($Token)) { $Token = [string]$config.token }
if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    $BaseUrl = 'http://127.0.0.1:' + [int]$config.port
}
$BaseUrl = $BaseUrl.TrimEnd('/')

$headers = @{ 'X-JARVIS-Token' = $Token }
$failed = 0

function Step([string]$Name, [scriptblock]$Action) {
    Write-Host ''
    Write-Host "== $Name ==" -ForegroundColor Cyan
    try {
        & $Action
    } catch {
        $script:failed++
        Write-Host "FAILED: $($_.Exception.Message)" -ForegroundColor Red
    }
}

Step 'GET /ping (no token)' {
    $response = Invoke-RestMethod -Uri "$BaseUrl/ping" -Method Get -TimeoutSec 5
    Write-Host ("OK: {0} {1} on port {2}" -f $response.name, $response.version, $response.port) -ForegroundColor Green
}

Step 'GET /health (token)' {
    $health = Invoke-RestMethod -Uri "$BaseUrl/health" -Method Get -Headers $headers -TimeoutSec 5
    Write-Host ("OK: mode={0} commands={1} host={2}" -f $health.mode, $health.allowlist_count, $health.host) -ForegroundColor Green
    if ($health.commands.Count -gt 0) {
        Write-Host ('    ' + (($health.commands | ForEach-Object { $_.name }) -join ', '))
    }
}

Step 'POST /execute whoami' {
    $body = @{
        request_id = [guid]::NewGuid().ToString()
        command    = 'whoami'
        raw        = 'whoami'
        args       = @{}
        client     = 'test-agent.ps1'
        app_version = 'test'
    } | ConvertTo-Json -Depth 5 -Compress
    $run = Invoke-RestMethod -Uri "$BaseUrl/execute" -Method Post -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 30
    if ($run.ok) {
        Write-Host ("OK: exit {0} in {1} ms" -f $run.exit_code, $run.duration_ms) -ForegroundColor Green
        if ($run.stdout) { Write-Host $run.stdout }
    } else {
        $script:failed++
        Write-Host ("FAILED: exit {0}, timed_out={1}" -f $run.exit_code, $run.timed_out) -ForegroundColor Red
        if ($run.stderr) { Write-Host $run.stderr -ForegroundColor Red }
        if ($run.error) { Write-Host $run.error -ForegroundColor Red }
    }
}

Write-Host ''
if ($failed -eq 0) {
    Write-Host 'All agent checks passed.' -ForegroundColor Green
    exit 0
}
Write-Host "$failed step(s) failed." -ForegroundColor Red
Write-Host 'If /ping works but /health returns 401, the token does not match config.json.' -ForegroundColor Yellow
exit 1
