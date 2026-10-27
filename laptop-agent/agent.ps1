#requires -Version 5.1
<#
.SYNOPSIS
    JARVIS laptop agent - a tiny HTTP server the Android app can talk to.

.DESCRIPTION
    Listens on a TCP port on this computer and accepts three routes:

        GET  /ping      liveness probe (no token) - used by the app's discovery
        GET  /health    agent name, mode and the commands it will run (token)
        POST /execute   run one command (token)

    By default only the named commands in config.json can run. Raw PowerShell
    is possible only when config.json says "mode": "free" - and the app has to
    opt in as well.

    The token is compared on every authenticated request. It is never written
    to agent.log and never echoed in a response.

.PARAMETER ConfigPath
    Path to config.json. Defaults to the file next to this script.

.PARAMETER Port
    Overrides the port in config.json. Useful for a quick test.
#>
[CmdletBinding()]
param(
    [string]$ConfigPath = '',
    [int]$Port = 0
)

$ErrorActionPreference = 'Stop'
$AgentVersion = '1.0.0'
$MaxBodyBytes = 262144
$MaxOutputChars = 40000

# $PSScriptRoot is not reliable inside a parameter default in Windows PowerShell
# 5.1, so resolve it here.
if ([string]::IsNullOrWhiteSpace($ConfigPath)) {
    $ConfigPath = Join-Path $PSScriptRoot 'config.json'
}

# --------------------------------------------------------------------- config

function Fail([string]$Message) {
    Write-Host ''
    Write-Host "ERROR: $Message" -ForegroundColor Red
    Write-Host ''
    exit 1
}

if (-not (Test-Path -LiteralPath $ConfigPath)) {
    Fail "config.json was not found at $ConfigPath. Run start-agent.ps1 once, or copy config.example.json to config.json."
}

try {
    $config = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    Fail "config.json is not valid JSON: $($_.Exception.Message)"
}

$token = [string]$config.token
if ([string]::IsNullOrWhiteSpace($token) -or $token -eq 'CHANGE_ME') {
    Fail 'config.json still has the placeholder token. Run start-agent.ps1, which generates one, or put a long random token in config.json.'
}
if ($token.Length -lt 16) {
    Fail 'The token in config.json is too short. Use at least 16 characters (start-agent.ps1 generates 32).'
}

$listenPort = if ($Port -gt 0) { $Port } else { [int]$config.port }
if ($listenPort -lt 1 -or $listenPort -gt 65535) { $listenPort = 8787 }

$mode = [string]$config.mode
if ([string]::IsNullOrWhiteSpace($mode)) { $mode = 'allowlist' }
$mode = $mode.ToLowerInvariant()
if ($mode -ne 'allowlist' -and $mode -ne 'free') {
    Fail "config.json mode must be `"allowlist`" or `"free`" (found `"$mode`")."
}

$agentName = [string]$config.name
if ([string]::IsNullOrWhiteSpace($agentName)) { $agentName = 'JARVIS laptop agent' }

$timeoutSeconds = [int]$config.timeout_seconds
if ($timeoutSeconds -lt 5) { $timeoutSeconds = 5 }
if ($timeoutSeconds -gt 600) { $timeoutSeconds = 600 }

$workingDirectory = [string]$config.working_directory
if ([string]::IsNullOrWhiteSpace($workingDirectory)) {
    $workingDirectory = [Environment]::GetFolderPath('UserProfile')
}
if (-not (Test-Path -LiteralPath $workingDirectory)) {
    Write-Host "WARN: working_directory '$workingDirectory' does not exist; using $env:USERPROFILE" -ForegroundColor Yellow
    $workingDirectory = $env:USERPROFILE
}

# name -> definition. A PSCustomObject from JSON is turned into a hashtable so
# lookups are simple and case behaviour is predictable.
$commands = @{}
if ($null -ne $config.commands) {
    foreach ($property in $config.commands.PSObject.Properties) {
        $definition = $property.Value
        if ($null -eq $definition) { continue }
        $script = [string]$definition.script
        if ([string]::IsNullOrWhiteSpace($script)) {
            Write-Host "WARN: command '$($property.Name)' has no script and is ignored" -ForegroundColor Yellow
            continue
        }
        $declaredArgs = @()
        if ($null -ne $definition.args) {
            foreach ($arg in $definition.args) { $declaredArgs += [string]$arg }
        }
        $requiredArgs = @()
        if ($null -ne $definition.required) {
            foreach ($arg in $definition.required) { $requiredArgs += [string]$arg }
        }
        $aliases = @()
        if ($null -ne $definition.aliases) {
            foreach ($alias in $definition.aliases) {
                if (-not [string]::IsNullOrWhiteSpace([string]$alias)) { $aliases += [string]$alias }
            }
        }
        $commands[$property.Name] = @{
            name        = $property.Name
            description = [string]$definition.description
            args        = $declaredArgs
            required    = $requiredArgs
            aliases     = $aliases
            preview     = if ([string]::IsNullOrWhiteSpace([string]$definition.preview)) { $script } else { [string]$definition.preview }
            script      = $script
        }
    }
}

if ($mode -eq 'allowlist' -and $commands.Count -eq 0) {
    Write-Host 'WARN: allowlist mode with no commands. The app will connect but every command will be refused.' -ForegroundColor Yellow
}

# Spoken phrases ("open notepad", "what is my battery") map to the same command,
# so the user does not have to remember the internal name. Aliases are optional
# and never grant anything on their own: they only point at an allowlisted entry.
$aliasMap = @{}
foreach ($key in $commands.Keys) {
    foreach ($alias in $commands[$key].aliases) {
        $lower = $alias.Trim().ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($lower)) { continue }
        if (-not $aliasMap.ContainsKey($lower)) { $aliasMap[$lower] = $key }
    }
}

# ---------------------------------------------------------------------- logging

$logPath = Join-Path $PSScriptRoot 'agent.log'

function Write-Log([string]$Message) {
    $line = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') + '  ' + $Message
    try {
        Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
    } catch {
        # A full disk must not take the agent down.
    }
}

# ------------------------------------------------------------------ http helper

function Send-Json {
    param(
        [System.Net.Sockets.NetworkStream]$Stream,
        [int]$Status,
        [string]$Reason,
        [object]$Payload
    )
    $json = if ($Payload -is [string]) { $Payload } else { $Payload | ConvertTo-Json -Depth 8 -Compress }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $header = "HTTP/1.1 $Status $Reason`r`n" +
        "Content-Type: application/json; charset=utf-8`r`n" +
        "Content-Length: $($bytes.Length)`r`n" +
        "Cache-Control: no-store`r`n" +
        "Connection: close`r`n`r`n"
    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
    $Stream.Write($headerBytes, 0, $headerBytes.Length)
    if ($bytes.Length -gt 0) { $Stream.Write($bytes, 0, $bytes.Length) }
    $Stream.Flush()
}

function Read-Request {
    param([System.Net.Sockets.NetworkStream]$Stream)

    $Stream.ReadTimeout = 20000
    $buffer = New-Object System.IO.MemoryStream
    $chunk = New-Object byte[] 8192
    $headerEnd = -1

    while ($true) {
        $read = $Stream.Read($chunk, 0, $chunk.Length)
        if ($read -le 0) { return $null }
        $buffer.Write($chunk, 0, $read)
        $all = $buffer.ToArray()
        for ($i = 3; $i -lt $all.Length; $i++) {
            if ($all[$i - 3] -eq 13 -and $all[$i - 2] -eq 10 -and $all[$i - 1] -eq 13 -and $all[$i] -eq 10) {
                $headerEnd = $i
                break
            }
        }
        if ($headerEnd -ge 0) { break }
        if ($buffer.Length -gt 65536) { return $null }
    }

    $all = $buffer.ToArray()
    $headerText = [System.Text.Encoding]::ASCII.GetString($all, 0, $headerEnd - 3)
    $lines = $headerText -split "`r`n"
    if ($lines.Count -lt 1) { return $null }

    $requestLine = $lines[0] -split ' '
    if ($requestLine.Count -lt 2) { return $null }

    $headers = @{}
    for ($i = 1; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        $colon = $line.IndexOf(':')
        if ($colon -le 0) { continue }
        $key = $line.Substring(0, $colon).Trim()
        $value = $line.Substring($colon + 1).Trim()
        if ($headers.ContainsKey($key)) {
            $headers[$key] = $headers[$key] + ', ' + $value
        } else {
            $headers[$key] = $value
        }
    }

    if ($headers.ContainsKey('Transfer-Encoding')) {
        return @{ error = 'chunked bodies are not supported' }
    }

    $contentLength = 0
    if ($headers.ContainsKey('Content-Length')) {
        $parsed = 0
        if (-not [int]::TryParse($headers['Content-Length'], [ref]$parsed)) {
            return @{ error = 'bad Content-Length' }
        }
        $contentLength = $parsed
    }
    if ($contentLength -lt 0 -or $contentLength -gt $MaxBodyBytes) {
        return @{ error = "body too large (limit $MaxBodyBytes bytes)" }
    }

    $bodyStart = $headerEnd + 1
    $have = $all.Length - $bodyStart
    $body = New-Object System.IO.MemoryStream
    if ($have -gt 0) { $body.Write($all, $bodyStart, $have) }
    $remaining = $contentLength - $have
    while ($remaining -gt 0) {
        $read = $Stream.Read($chunk, 0, [Math]::Min($chunk.Length, $remaining))
        if ($read -le 0) { break }
        $body.Write($chunk, 0, $read)
        $remaining -= $read
    }

    $target = $requestLine[1]
    $query = ''
    $questionMark = $target.IndexOf('?')
    if ($questionMark -ge 0) {
        $query = $target.Substring($questionMark + 1)
        $target = $target.Substring(0, $questionMark)
    }

    return @{
        method  = $requestLine[0].ToUpperInvariant()
        path    = $target.TrimEnd('/')
        query   = $query
        headers = $headers
        body    = [System.Text.Encoding]::UTF8.GetString($body.ToArray())
    }
}

function Test-Token($Request) {
    $given = ''
    if ($Request.headers.ContainsKey('X-JARVIS-Token')) {
        $given = [string]$Request.headers['X-JARVIS-Token']
    }
    if ([string]::IsNullOrEmpty($given)) { return $false }
    # Case-sensitive comparison, same length first so a near miss is not a match.
    if ($given.Length -ne $token.Length) { return $false }
    return [string]::Equals($given, $token, [System.StringComparison]::Ordinal)
}

# ---------------------------------------------------------------- command run

function Get-PowerShellExe {
    $candidate = Join-Path $PSHOME 'powershell.exe'
    if (Test-Path -LiteralPath $candidate) { return $candidate }
    $candidate = Join-Path $PSHOME 'pwsh.exe'
    if (Test-Path -LiteralPath $candidate) { return $candidate }
    return 'powershell.exe'
}

function Invoke-CommandScript {
    param(
        [string]$ScriptText,
        [hashtable]$Arguments,
        [string]$RequestId
    )

    $tempDir = Join-Path $env:TEMP 'jarvis-laptop-agent'
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $scriptFile = Join-Path $tempDir (([guid]::NewGuid().ToString('N')) + '.ps1')

    $prelude = @'
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
try { [Console]::InputEncoding = [System.Text.Encoding]::UTF8 } catch { }
'@
    Set-Content -LiteralPath $scriptFile -Value ($prelude + "`r`n" + $ScriptText) -Encoding UTF8

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = Get-PowerShellExe
    $psi.Arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $scriptFile + '"'
    $psi.WorkingDirectory = $workingDirectory
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8

    $psi.EnvironmentVariables['JARVIS_REQUEST_ID'] = $RequestId
    $psi.EnvironmentVariables['JARVIS_AGENT_VERSION'] = $AgentVersion
    $psi.EnvironmentVariables['JARVIS_WORKDIR'] = $workingDirectory
    $psi.EnvironmentVariables['JARVIS_ARGS_JSON'] = ($Arguments | ConvertTo-Json -Compress)

    foreach ($key in $Arguments.Keys) {
        # open_app -> OPEN_APP, so a script can use $env:JARVIS_ARG_OPEN_APP.
        $safe = ($key.ToUpperInvariant() -replace '[^A-Z0-9_]', '_')
        $psi.EnvironmentVariables['JARVIS_ARG_' + $safe] = [string]$Arguments[$key]
    }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $process = $null
    $timedOut = $false
    $stdout = ''
    $stderr = ''
    $exitCode = -1

    try {
        $process = [System.Diagnostics.Process]::Start($psi)
        $outTask = $process.StandardOutput.ReadToEndAsync()
        $errTask = $process.StandardError.ReadToEndAsync()

        $timeoutMs = $timeoutSeconds * 1000
        if (-not $process.WaitForExit($timeoutMs)) {
            $timedOut = $true
            try { & taskkill.exe /PID $process.Id /T /F 2>&1 | Out-Null } catch { }
            if (-not $process.WaitForExit(5000)) {
                try { $process.Kill() } catch { }
            }
        }
        try { $stdout = $outTask.Result } catch { $stdout = '' }
        try { $stderr = $errTask.Result } catch { $stderr = '' }
        if (-not $timedOut) { $exitCode = $process.ExitCode }
    } catch {
        $stderr = $_.Exception.Message
    } finally {
        $sw.Stop()
        Remove-Item -LiteralPath $scriptFile -Force -ErrorAction SilentlyContinue
        if ($null -ne $process) { $process.Dispose() }
    }

    $outTrimmed = $stdout.TrimEnd()
    if ($outTrimmed.Length -gt $MaxOutputChars) {
        $outTrimmed = $outTrimmed.Substring(0, $MaxOutputChars) + "`n... output truncated by the agent ..."
    }
    $errTrimmed = $stderr.TrimEnd()
    if ($errTrimmed.Length -gt $MaxOutputChars) {
        $errTrimmed = $errTrimmed.Substring(0, $MaxOutputChars) + "`n... output truncated by the agent ..."
    }

    return @{
        ok          = (-not $timedOut -and $exitCode -eq 0)
        exit_code   = $exitCode
        stdout      = $outTrimmed
        stderr      = $errTrimmed
        duration_ms = [int]$sw.ElapsedMilliseconds
        timed_out   = $timedOut
    }
}

# ------------------------------------------------------------------- routing

function Get-AgentInfo {
    $list = @()
    foreach ($key in ($commands.Keys | Sort-Object)) {
        $command = $commands[$key]
        $list += @{
            name        = $command.name
            description = $command.description
            args        = $command.args
            required    = $command.required
            aliases     = $command.aliases
            preview     = $command.preview
        }
    }
    return @{
        name              = $agentName
        version           = $AgentVersion
        mode              = $mode
        host              = $env:COMPUTERNAME
        working_directory = $workingDirectory
        allowlist_count   = $commands.Count
        commands          = $list
    }
}

function Invoke-Execute($Request) {
    if (-not (Test-Token $Request)) {
        return @{ status = 401; reason = 'Unauthorized'; payload = @{ ok = $false; error = 'The X-JARVIS-Token header is missing or does not match config.json.' } }
    }

    $payload = $null
    try {
        $payload = $Request.body | ConvertFrom-Json
    } catch {
        return @{ status = 400; reason = 'Bad Request'; payload = @{ ok = $false; error = 'The body is not valid JSON.' } }
    }
    if ($null -eq $payload) {
        return @{ status = 400; reason = 'Bad Request'; payload = @{ ok = $false; error = 'The body is empty.' } }
    }

    $requestId = [string]$payload.request_id
    if ([string]::IsNullOrWhiteSpace($requestId)) { $requestId = [guid]::NewGuid().ToString() }
    $commandName = [string]$payload.command
    $raw = [string]$payload.raw

    $arguments = @{}
    if ($null -ne $payload.args) {
        foreach ($property in $payload.args.PSObject.Properties) {
            $arguments[$property.Name] = [string]$property.Value
        }
    }

    if ($mode -eq 'free') {
        $scriptText = $raw
        if ([string]::IsNullOrWhiteSpace($scriptText)) { $scriptText = $commandName }
        if ([string]::IsNullOrWhiteSpace($scriptText)) {
            return @{ status = 400; reason = 'Bad Request'; payload = @{ ok = $false; request_id = $requestId; error = 'There is nothing to run.' } }
        }
        $label = $scriptText
    } else {
        if ([string]::IsNullOrWhiteSpace($commandName)) {
            return @{ status = 400; reason = 'Bad Request'; payload = @{ ok = $false; request_id = $requestId; error = 'No command name was sent.' } }
        }

        # Exact name wins; then an alias of the name; then an alias of the raw
        # sentence. Anything else is refused.
        if (-not $commands.ContainsKey($commandName)) {
            $nameLower = $commandName.Trim().ToLowerInvariant()
            if ($aliasMap.ContainsKey($nameLower)) { $commandName = $aliasMap[$nameLower] }
        }
        if (-not $commands.ContainsKey($commandName) -and -not [string]::IsNullOrWhiteSpace($raw)) {
            $rawLower = $raw.Trim().ToLowerInvariant()
            if ($aliasMap.ContainsKey($rawLower)) { $commandName = $aliasMap[$rawLower] }
        }
        if (-not $commands.ContainsKey($commandName)) {
            $available = ($commands.Keys | Sort-Object) -join ', '
            Write-Log "REFUSED  $commandName  (not in allowlist)"
            return @{
                status  = 403
                reason  = 'Forbidden'
                payload = @{
                    ok         = $false
                    request_id = $requestId
                    error      = "The agent does not allow '$commandName'. Allowed: $available"
                }
            }
        }
        $definition = $commands[$commandName]

        foreach ($name in $definition.args) {
            if (-not $arguments.ContainsKey($name)) { $arguments[$name] = '' }
        }
        $unknown = @($arguments.Keys | Where-Object { $definition.args -notcontains $_ })
        if ($unknown.Count -gt 0) {
            return @{
                status  = 400
                reason  = 'Bad Request'
                payload = @{
                    ok         = $false
                    request_id = $requestId
                    error      = "Unknown argument(s) for '$commandName': $($unknown -join ', '). Declared: $($definition.args -join ', ')"
                }
            }
        }
        foreach ($name in $definition.required) {
            if ([string]::IsNullOrWhiteSpace([string]$arguments[$name])) {
                return @{
                    status  = 400
                    reason  = 'Bad Request'
                    payload = @{
                        ok         = $false
                        request_id = $requestId
                        error      = "'$commandName' needs the argument '$name'."
                    }
                }
            }
        }
        $scriptText = $definition.script
        $label = $commandName
        if ($arguments.Count -gt 0) {
            $label += ' ' + (($arguments.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' ')
        }
    }

    Write-Log "RUN      $requestId  $label"

    $result = Invoke-CommandScript -ScriptText $scriptText -Arguments $arguments -RequestId $requestId

    $statusWord = if ($result.ok) { 'ok' } elseif ($result.timed_out) { 'timeout' } else { "exit $($result.exit_code)" }
    Write-Log "DONE     $requestId  $statusWord  $($result.duration_ms)ms"

    $errorText = ''
    if ($result.timed_out) {
        $errorText = "The command did not finish within $timeoutSeconds seconds and was stopped."
    } elseif (-not $result.ok) {
        $errorText = "The command exited with code $($result.exit_code)."
    }

    return @{
        status  = 200
        reason  = 'OK'
        payload = @{
            ok          = $result.ok
            request_id  = $requestId
            command     = $label
            exit_code   = $result.exit_code
            stdout      = $result.stdout
            stderr      = $result.stderr
            duration_ms = $result.duration_ms
            timed_out   = $result.timed_out
            error       = $errorText
        }
    }
}

# ----------------------------------------------------------------------- serve

$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $listenPort)
try {
    $listener.Start()
} catch {
    Fail "Could not listen on port $listenPort. Another program may be using it, or the port needs admin rights. $($_.Exception.Message)"
}

$addresses = @()
try {
    $addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        Select-Object -ExpandProperty IPAddress
} catch { }

Write-Host ''
Write-Host '  JARVIS laptop agent' -ForegroundColor Cyan
Write-Host "  mode:    $mode"
Write-Host "  name:    $agentName"
Write-Host "  command: $($commands.Count) configured"
Write-Host "  workdir: $workingDirectory"
Write-Host "  timeout: $timeoutSeconds s"
Write-Host "  log:     $logPath"
Write-Host ''
if ($addresses.Count -gt 0) {
    foreach ($address in $addresses) {
        Write-Host "  address: http://${address}:$listenPort" -ForegroundColor Green
    }
} else {
    Write-Host "  address: http://<this-pc-ip>:$listenPort" -ForegroundColor Yellow
}
Write-Host ''
Write-Host '  Paste the address and the token into the app: Automations > Laptop Control.' -ForegroundColor DarkGray
Write-Host '  Press Ctrl+C to stop.' -ForegroundColor DarkGray
Write-Host ''

Write-Log "START    mode=$mode port=$listenPort commands=$($commands.Count)"

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        $stream = $null
        $request = $null
        $status = 200
        $path = '(unread)'
        try {
            $client.NoDelay = $true
            $stream = $client.GetStream()
            $stream.WriteTimeout = 30000
            $request = Read-Request -Stream $stream

            if ($null -eq $request) {
                $status = 400
                continue
            }
            if ($request.ContainsKey('error')) {
                $status = 413
                Send-Json -Stream $stream -Status 413 -Reason 'Payload Too Large' -Payload @{ ok = $false; error = $request.error }
                continue
            }

            $path = "$($request.method) $($request.path)"

            switch ($request.path) {
                '/ping' {
                    if ($request.method -ne 'GET') {
                        $status = 405
                        Send-Json -Stream $stream -Status 405 -Reason 'Method Not Allowed' -Payload @{ ok = $false; error = 'Use GET /ping.' }
                        continue
                    }
                    Send-Json -Stream $stream -Status 200 -Reason 'OK' -Payload @{
                        ok      = $true
                        name    = $agentName
                        version = $AgentVersion
                        port    = $listenPort
                        time    = (Get-Date).ToString('o')
                    }
                    continue
                }
                '/health' {
                    if ($request.method -ne 'GET') {
                        $status = 405
                        Send-Json -Stream $stream -Status 405 -Reason 'Method Not Allowed' -Payload @{ ok = $false; error = 'Use GET /health.' }
                        continue
                    }
                    if (-not (Test-Token $request)) {
                        $status = 401
                        Send-Json -Stream $stream -Status 401 -Reason 'Unauthorized' -Payload @{ ok = $false; error = 'The X-JARVIS-Token header is missing or does not match config.json.' }
                        continue
                    }
                    Send-Json -Stream $stream -Status 200 -Reason 'OK' -Payload (Get-AgentInfo)
                    continue
                }
                '/execute' {
                    if ($request.method -ne 'POST') {
                        $status = 405
                        Send-Json -Stream $stream -Status 405 -Reason 'Method Not Allowed' -Payload @{ ok = $false; error = 'Use POST /execute.' }
                        continue
                    }
                    $outcome = Invoke-Execute $request
                    $status = $outcome.status
                    Send-Json -Stream $stream -Status $outcome.status -Reason $outcome.reason -Payload $outcome.payload
                    continue
                }
                '/' {
                    Send-Json -Stream $stream -Status 200 -Reason 'OK' -Payload @{
                        ok      = $true
                        name    = $agentName
                        version = $AgentVersion
                        routes  = @('/ping', '/health', '/execute')
                    }
                    continue
                }
                default {
                    $status = 404
                    Send-Json -Stream $stream -Status 404 -Reason 'Not Found' -Payload @{
                        ok    = $false
                        error = "No route at '$($request.path)'. Use /ping, /health or /execute."
                    }
                    continue
                }
            }
        } catch {
            $status = 500
            $message = $_.Exception.Message
            if ($message -match 'aborted|forcibly closed|broken pipe|transport connection|closed by the remote') {
                # A phone that walked out of Wi-Fi range, or a browser tab closed
                # mid-request. Not an agent fault, so do not cry ERROR.
                Write-Log "CLOSED   $path"
            } else {
                Write-Log "ERROR    $path  $message"
            }
            if ($null -ne $stream) {
                try {
                    Send-Json -Stream $stream -Status 500 -Reason 'Internal Server Error' -Payload @{ ok = $false; error = 'The agent hit an internal error. See agent.log.' }
                } catch { }
            }
        } finally {
            try { if ($null -ne $stream) { $stream.Dispose() } } catch { }
            try { $client.Close() } catch { }
        }
    }
} finally {
    $listener.Stop()
    Write-Log 'STOP'
    Write-Host ''
    Write-Host '  Agent stopped.' -ForegroundColor DarkGray
}
