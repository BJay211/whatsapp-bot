# Bonjee Bot - live download / build monitor.
# Opens in its own window and refreshes every 3 seconds.

$ErrorActionPreference = "SilentlyContinue"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$app  = Join-Path $root "apps\android-notifier"

function FolderMB($p) {
    if (-not (Test-Path $p)) { return 0 }
    $s = (Get-ChildItem $p -Recurse -File -ErrorAction SilentlyContinue |
          Measure-Object -Property Length -Sum).Sum
    if ($null -eq $s) { return 0 }
    return [math]::Round($s / 1MB, 1)
}

$paths = [ordered]@{
    "Android SDK"       = Join-Path $app ".toolchain\android-sdk"
    "Downloaded zips"   = Join-Path $app ".toolchain\downloads"
    "Gradle cache"      = Join-Path $env:USERPROFILE ".gradle\caches\modules-2"
    "Gradle wrapper"    = Join-Path $env:USERPROFILE ".gradle\wrapper"
    "App build output"  = Join-Path $app "app\build"
}

$prev = @{}
foreach ($k in $paths.Keys) { $prev[$k] = FolderMB $paths[$k] }
$prevTime = Get-Date
$grandPrev = ($prev.Values | Measure-Object -Sum).Sum

$apk = Join-Path $app "BonjeeNotifier.apk"
$started = Get-Date

while ($true) {
    Start-Sleep -Seconds 3
    $now = Get-Date
    $dt = ($now - $prevTime).TotalSeconds
    if ($dt -le 0) { $dt = 3 }

    $rows = @()
    $grandNow = 0
    foreach ($k in $paths.Keys) {
        $mb = FolderMB $paths[$k]
        $delta = $mb - $prev[$k]
        $speed = $delta / $dt
        $rows += [pscustomobject]@{ Name = $k; MB = $mb; Delta = $delta; Speed = $speed }
        $grandNow += $mb
        $prev[$k] = $mb
    }
    $grandSpeed = ($grandNow - $grandPrev) / $dt
    $grandPrev = $grandNow
    $prevTime = $now

    Clear-Host
    Write-Host ""
    Write-Host "  BONJEE BOT  -  live build monitor" -ForegroundColor Cyan
    Write-Host "  ============================================================" -ForegroundColor DarkCyan
    Write-Host ("  Clock: {0}      Watching for: {1:mm\:ss}" -f $now.ToString("HH:mm:ss"), ($now - $started))
    Write-Host ""

    Write-Host ("  {0,-20} {1,12} {2,14}" -f "Folder", "Total size", "Right now")
    Write-Host "  ------------------------------------------------------------" -ForegroundColor DarkGray
    foreach ($r in $rows) {
        $colour = if ($r.Speed -gt 0.5) { "Green" }
                  elseif ($r.Speed -gt 0.05) { "Yellow" }
                  elseif ($r.Speed -gt 0) { "DarkYellow" }
                  else { "DarkGray" }
        $speedText = if ($r.Speed -gt 0) { "{0,8:N2} MB/s" -f $r.Speed } else { "     idle" }
        Write-Host ("  {0,-20} {1,9:N1} MB  {2}" -f $r.Name, $r.MB, $speedText) -ForegroundColor $colour
    }
    Write-Host "  ------------------------------------------------------------" -ForegroundColor DarkGray
    $totalColour = if ($grandSpeed -gt 0.5) { "Green" } elseif ($grandSpeed -gt 0) { "Yellow" } else { "DarkGray" }
    Write-Host ("  {0,-20} {1,9:N1} MB  {2,8:N2} MB/s" -f "TOTAL", $grandNow, $grandSpeed) -ForegroundColor $totalColour
    Write-Host ""

    # --- network + process state -------------------------------------------
    $net = Get-NetAdapterStatistics -ErrorAction SilentlyContinue |
           Where-Object { $_.ReceivedBytes -gt 0 }
    if ($net) {
        $rx = ($net | Measure-Object -Property ReceivedBytes -Sum).Sum
        if ($script:lastRx -and $dt -gt 0) {
            $mbps = (($rx - $script:lastRx) / $dt) / 1MB
            Write-Host ("  Network received: {0,8:N2} MB/s" -f $mbps) -ForegroundColor Cyan
        }
        $script:lastRx = $rx
    }

    $dl = Get-CimInstance Win32_Process -Filter "Name='curl.exe'" -ErrorAction SilentlyContinue
    $java = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue
    $gradle = Get-Process -Name "gradle*" -ErrorAction SilentlyContinue
    Write-Host ("  curl downloading : {0}" -f $(if ($dl) { "YES" } else { "no" })) -ForegroundColor $(if ($dl) { "Green" } else { "DarkGray" })
    Write-Host ("  sdkmanager (java): {0}" -f $(if ($java) { "YES" } else { "no" })) -ForegroundColor $(if ($java) { "Green" } else { "DarkGray" })
    Write-Host ("  gradle running   : {0}" -f $(if ($gradle) { "YES" } else { "no" })) -ForegroundColor $(if ($gradle) { "Green" } else { "DarkGray" })
    Write-Host ""

    if (Test-Path $apk) {
        $sz = [math]::Round((Get-Item $apk).Length / 1MB, 2)
        Write-Host "  *** APK IS READY: $apk ($sz MB) ***" -ForegroundColor Green
        Write-Host "      Copy it to your phone and tap it to install." -ForegroundColor Green
    } else {
        Write-Host "  APK: not built yet" -ForegroundColor DarkGray
    }

    Write-Host ""
    Write-Host "  (this window refreshes by itself - you can leave it open)" -ForegroundColor DarkGray
}
