#requires -Version 5.1
<#
.SYNOPSIS
    Starts the JARVIS laptop agent, creating config.json and a token on first run.

.DESCRIPTION
    First run:
      - copies config.example.json to config.json
      - generates a 32-character random token and stores it there
      - prints the addresses the phone can use

    Later runs reuse the same config, so the token in the app keeps working.

.PARAMETER Port
    Override the port for this run only.

.PARAMETER AllowFirewall
    Try to add a Windows Firewall rule for the port. Needs an elevated shell;
    if it fails the script says so and prints the command to run yourself.
#>
[CmdletBinding()]
param(
    [int]$Port = 0,
    [switch]$AllowFirewall
)

$ErrorActionPreference = 'Stop'

$configPath = Join-Path $PSScriptRoot 'config.json'
$examplePath = Join-Path $PSScriptRoot 'config.example.json'

if (-not (Test-Path -LiteralPath $examplePath)) {
    Write-Host 'ERROR: config.example.json is missing; cannot create config.json.' -ForegroundColor Red
    exit 1
}

function New-Token {
    # 32 hex characters, no punctuation to mistype into the phone.
    return ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N').Substring(0, 8))
}

if (-not (Test-Path -LiteralPath $configPath)) {
    Copy-Item -LiteralPath $examplePath -Destination $configPath
    Write-Host 'Created config.json from config.example.json.' -ForegroundColor Green
}

$raw = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8
try {
    $config = $raw | ConvertFrom-Json
} catch {
    Write-Host "ERROR: config.json is not valid JSON: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

$token = [string]$config.token
if ([string]::IsNullOrWhiteSpace($token) -or $token -eq 'CHANGE_ME' -or $token.Length -lt 16) {
    $token = New-Token
    $config.token = $token
    $json = $config | ConvertTo-Json -Depth 20
    Set-Content -LiteralPath $configPath -Value $json -Encoding UTF8
    Write-Host 'Generated a new token and saved it in config.json.' -ForegroundColor Green
}

$port = if ($Port -gt 0) { $Port } else { [int]$config.port }
if ($port -lt 1 -or $port -gt 65535) { $port = 8787 }

if ($AllowFirewall) {
    $ruleName = 'JARVIS laptop agent'
    Write-Host "Adding firewall rule '$ruleName' for TCP $port ..." -ForegroundColor DarkGray
    try {
        $existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
        if ($null -ne $existing) {
            Write-Host 'A rule with that name already exists; leaving it alone.' -ForegroundColor DarkGray
        } else {
            New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow `
                -Protocol TCP -LocalPort $port -Profile Private | Out-Null
            Write-Host 'Firewall rule added for private networks.' -ForegroundColor Green
        }
    } catch {
        Write-Host "Could not add the firewall rule: $($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host 'Run PowerShell as Administrator and try again, or allow the port when Windows asks.' -ForegroundColor Yellow
    }
}

$addresses = @()
try {
    $addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        Select-Object -ExpandProperty IPAddress
} catch { }

Write-Host ''
Write-Host '  JARVIS laptop agent - starting' -ForegroundColor Cyan
Write-Host ''
if ($addresses.Count -gt 0) {
    foreach ($address in $addresses) {
        Write-Host "  App address:  http://${address}:$port" -ForegroundColor Green
    }
} else {
    Write-Host "  App address:  http://<this-pc-ip>:$port" -ForegroundColor Yellow
}
Write-Host "  Token:        $token" -ForegroundColor Green
Write-Host ''
Write-Host '  In the app: Automations > Laptop Control' -ForegroundColor DarkGray
Write-Host '  Paste the address and token, then tap SAVE and TEST.' -ForegroundColor DarkGray
Write-Host ''
if (-not $AllowFirewall) {
    Write-Host '  If the phone cannot reach it, allow the port on Private networks:' -ForegroundColor DarkGray
    Write-Host "    New-NetFirewallRule -DisplayName 'JARVIS laptop agent' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port -Profile Private" -ForegroundColor DarkGray
    Write-Host '  (Run that in an Administrator PowerShell, or rerun this script with -AllowFirewall.)' -ForegroundColor DarkGray
    Write-Host ''
}

& (Join-Path $PSScriptRoot 'agent.ps1') -ConfigPath $configPath -Port $port
exit $LASTEXITCODE
