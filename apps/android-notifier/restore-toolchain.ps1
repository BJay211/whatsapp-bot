# Bonjee Bot - rebuild the local Android toolchain (JDK + Gradle + SDK).
#
# The toolchain folder was damaged, so bin\java.exe and bin\gradle.bat are gone.
# This script re-downloads the three archives, re-extracts them, then hands the
# SDK packages over to fetch-sdk.ps1 (which uses plain curl because sdkmanager
# stalls on this connection).
#
# Safe to re-run: complete downloads are skipped, truncated ones are discarded.

$ErrorActionPreference = "Stop"

$Root       = Split-Path -Parent $MyInvocation.MyCommand.Path
$Tool       = Join-Path $Root ".toolchain"
$Dl         = Join-Path $Tool "downloads"
$Jdk        = Join-Path $Tool "jdk"
$GradleHome = Join-Path $Tool "gradle-8.7"

New-Item -ItemType Directory -Force -Path $Dl | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Say($m)  { Write-Output ("[restore] " + $m) }
function Fail($m) { Write-Output ("[restore] ERROR: " + $m); exit 1 }

function Test-ZipOk($p) {
    if (-not (Test-Path $p)) { return $false }
    try {
        $z = [System.IO.Compression.ZipFile]::OpenRead($p)
        $n = $z.Entries.Count
        $z.Dispose()
        return ($n -gt 0)
    } catch { return $false }
}

# Download one file with a live MB / MB-per-second read-out.
function Get-Asset($url, $target, $expectBytes) {
    if ((Test-Path $target) -and ((Get-Item $target).Length -ge ($expectBytes * 0.97)) -and (Test-ZipOk $target)) {
        Say ("{0} already complete ({1:N1} MB)" -f (Split-Path -Leaf $target), ((Get-Item $target).Length / 1MB))
        return
    }

    for ($attempt = 1; $attempt -le 10; $attempt++) {
        Say ("Downloading {0} ({1:N1} MB), attempt {2}" -f (Split-Path -Leaf $target), ($expectBytes / 1MB), $attempt)

        $job = Start-Job -ScriptBlock {
            param($u, $t)
            & curl.exe -L --fail --silent --show-error --retry 5 --retry-delay 3 `
                --connect-timeout 30 --speed-limit 1024 --speed-time 90 --max-time 7200 `
                -C - -o $t $u
            $LASTEXITCODE
        } -ArgumentList $url, $target

        $prev = 0
        $t0   = Get-Date
        while ($job.State -eq 'Running') {
            Start-Sleep -Seconds 10
            $sz = if (Test-Path $target) { (Get-Item $target).Length } else { 0 }
            $pct = if ($expectBytes -gt 0) { 100.0 * $sz / $expectBytes } else { 0 }
            $rate = ($sz - $prev) / 10 / 1MB
            Say ("  {0,7:N1} / {1,7:N1} MB  {2,5:N1}%   {3,5:N2} MB/s" -f ($sz / 1MB), ($expectBytes / 1MB), $pct, $rate)
            $prev = $sz
        }

        $code = (Receive-Job $job | Select-Object -Last 1)
        Remove-Job $job -Force -ErrorAction SilentlyContinue
        if ($null -eq $code) { $code = 1 }

        $sz = if (Test-Path $target) { (Get-Item $target).Length } else { 0 }
        if ($code -eq 0 -and $sz -ge ($expectBytes * 0.97) -and (Test-ZipOk $target)) {
            Say ("{0} OK ({1:N1} MB)" -f (Split-Path -Leaf $target), ($sz / 1MB))
            return
        }

        Say ("  attempt {0} incomplete (curl={1}, {2:N1} MB)" -f $attempt, $code, ($sz / 1MB))
        if ((Test-Path $target) -and -not (Test-ZipOk $target)) {
            Remove-Item $target -Force -ErrorAction SilentlyContinue
            Say "  partial archive was corrupt - restarting from zero"
        }
        Start-Sleep -Seconds 5
    }
    Fail ("Could not download " + (Split-Path -Leaf $target))
}

# --------------------------------------------------------------- clean damage
foreach ($d in @($Jdk, $GradleHome)) {
    if ((Test-Path $d) -and -not (Test-Path (Join-Path $d "bin"))) {
        Say ("Removing damaged folder " + (Split-Path -Leaf $d))
        Remove-Item $d -Recurse -Force
    }
}

# ------------------------------------------------------------------ downloads
Get-Asset "https://cdn.azul.com/zulu/bin/zulu17.54.21-ca-jdk17.0.13-win_x64.zip" (Join-Path $Dl "jdk17.zip")   195583839
Get-Asset "https://services.gradle.org/distributions/gradle-8.7-bin.zip"          (Join-Path $Dl "gradle.zip")  134184980

# ----------------------------------------------------------------- extraction
if (-not (Test-Path (Join-Path $Jdk "bin\java.exe"))) {
    Say "Extracting JDK 17 ..."
    $tmp = Join-Path $Tool "jdk-tmp"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path (Join-Path $Dl "jdk17.zip") -DestinationPath $tmp -Force
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (-not $inner) { Fail "JDK archive had no top-level folder" }
    New-Item -ItemType Directory -Force -Path $Jdk | Out-Null
    Copy-Item -Path (Join-Path $inner.FullName "*") -Destination $Jdk -Recurse -Force
    Remove-Item $tmp -Recurse -Force
}
if (-not (Test-Path (Join-Path $Jdk "bin\java.exe"))) { Fail "java.exe still missing" }
$env:JAVA_HOME = $Jdk
$env:PATH = (Join-Path $Jdk "bin") + ";" + $env:PATH
Say ("java: " + (& (Join-Path $Jdk "bin\java.exe") -version 2>&1 | Select-Object -First 1))

if (-not (Test-Path (Join-Path $GradleHome "bin\gradle.bat"))) {
    Say "Extracting Gradle 8.7 ..."
    Expand-Archive -Path (Join-Path $Dl "gradle.zip") -DestinationPath $Tool -Force
}
if (-not (Test-Path (Join-Path $GradleHome "bin\gradle.bat"))) { Fail "gradle.bat still missing" }
Say "Gradle ready"

# ------------------------------------------------------------------ android sdk
Say "Handing over to fetch-sdk.ps1 ..."
& powershell -ExecutionPolicy Bypass -NoProfile -File (Join-Path $Root "fetch-sdk.ps1")
if ($LASTEXITCODE -ne 0) { Fail "fetch-sdk.ps1 failed" }

# --------------------------------------------------------------------- verify
$ok = @{
    java   = Test-Path (Join-Path $Jdk "bin\java.exe")
    gradle = Test-Path (Join-Path $GradleHome "bin\gradle.bat")
    jar    = Test-Path (Join-Path $Tool "android-sdk\platforms\android-34\android.jar")
    btools = Test-Path (Join-Path $Tool "android-sdk\build-tools\34.0.0\aapt2.exe")
    adb    = Test-Path (Join-Path $Tool "android-sdk\platform-tools\adb.exe")
}
Say "---------------------------------------------"
foreach ($k in $ok.Keys) { Say ("{0,-7} {1}" -f $k, $(if ($ok[$k]) { "OK" } else { "MISSING" })) }
if ($ok.Values -contains $false) { Fail "toolchain still incomplete" }
Say "Toolchain restored."
