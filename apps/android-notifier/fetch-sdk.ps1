# Bonjee Bot - fetch the Android SDK packages directly with curl.
#
# sdkmanager (a Java tool) kept stalling at 0 bytes on this connection, while
# curl works fine, so we download the exact ZIPs Google publishes, extract them
# into the right folders and write the package.xml metadata AGP expects.
#
# Safe to re-run: finished downloads are skipped, corrupt ones are discarded.

$ErrorActionPreference = "Stop"

$Root   = Split-Path -Parent $MyInvocation.MyCommand.Path
$Tool   = Join-Path $Root ".toolchain"
$Sdk    = Join-Path $Tool "android-sdk"
$Dl     = Join-Path $Tool "downloads\sdk"
$Jdk    = Join-Path $Tool "jdk"
$Base   = "https://dl.google.com/android/repository"

New-Item -ItemType Directory -Force -Path $Sdk, $Dl | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Say($m) { Write-Host "[sdk] $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "[sdk] ERROR: $m" -ForegroundColor Red; exit 1 }

function Test-ZipOk($p) {
    if (-not (Test-Path $p)) { return $false }
    try {
        $z = [System.IO.Compression.ZipFile]::OpenRead($p); $n = $z.Entries.Count; $z.Dispose()
        return ($n -gt 0)
    } catch { return $false }
}

# name, remote file, minimum size in MB
$packages = @(
    @{ Key = "platforms;android-34";  File = "platform-34-ext7_r03.zip";     MinMB = 55; Dest = "platforms\android-34" },
    @{ Key = "build-tools;34.0.0";    File = "build-tools_r34-windows.zip";  MinMB = 50; Dest = "build-tools\34.0.0" },
    @{ Key = "platform-tools";        File = "platform-tools_r37.0.1-win.zip"; MinMB = 7; Dest = "platform-tools" }
)

foreach ($p in $packages) {
    $zip = Join-Path $Dl $p.File
    $min = $p.MinMB * 1MB

    if ((Test-Path $zip) -and ((Get-Item $zip).Length -ge $min) -and (Test-ZipOk $zip)) {
        Say "$($p.File) already downloaded"
        continue
    }

    Say "Downloading $($p.File) (about $($p.MinMB) MB)..."
    $expectedBytes = $min
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        $job = Start-Job -ScriptBlock {
            param($u, $t)
            & curl.exe -L --fail --silent --show-error --retry 3 --retry-delay 3 --connect-timeout 30 `
                --speed-limit 2048 --speed-time 60 --max-time 3600 -C - -o $t $u
            $LASTEXITCODE
        } -ArgumentList "$Base/$($p.File)", $zip

        $prev = if (Test-Path $zip) { (Get-Item $zip).Length } else { 0 }
        while ($job.State -eq 'Running') {
            Start-Sleep -Seconds 5
            $sz = if (Test-Path $zip) { (Get-Item $zip).Length } else { 0 }
            $pct = [math]::Min(100.0, 100.0 * $sz / $expectedBytes)
            $rate = ($sz - $prev) / 5 / 1MB
            Say ("  {0,7:N1} / {1,7:N1} MB  {2,5:N1}%  {3,5:N2} MB/s" -f ($sz / 1MB), ($expectedBytes / 1MB), $pct, $rate)
            $prev = $sz
        }

        $code = (Receive-Job $job | Select-Object -Last 1)
        Remove-Job $job -Force -ErrorAction SilentlyContinue
        if ($null -eq $code) { $code = 1 }

        $size = if (Test-Path $zip) { (Get-Item $zip).Length } else { 0 }
        if ($code -eq 0 -and $size -ge $min -and (Test-ZipOk $zip)) { break }
        Say ("  attempt {0}: curl={1}, {2:N1} MB - retrying" -f $attempt, $code, ($size / 1MB))
        if ((Test-Path $zip) -and -not (Test-ZipOk $zip)) {
            Remove-Item $zip -Force -ErrorAction SilentlyContinue
            Say "  discarded a corrupt partial file"
        }
        Start-Sleep -Seconds 4
    }

    if (-not (Test-ZipOk $zip)) { Fail "$($p.File) could not be downloaded." }
    Say "$($p.File) OK ($([math]::Round((Get-Item $zip).Length/1MB,1)) MB)"
}

# ---------------------------------------------------------------- extraction
foreach ($p in $packages) {
    $zip = Join-Path $Dl $p.File
    $dest = Join-Path $Sdk $p.Dest

    if (Test-Path (Join-Path $dest "source.properties")) {
        Say "$($p.Key) already extracted"
        continue
    }

    Say "Extracting $($p.File) -> $($p.Dest)"
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    $tmp = Join-Path $Tool ("ex-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
    Expand-Archive -Path $zip -DestinationPath $tmp -Force

    # The archives contain exactly one top-level folder whose name often does not
    # match the SDK folder we need (e.g. "android-14" for API 34).
    $dirs = @(Get-ChildItem $tmp -Directory)
    if ($dirs.Count -eq 1 -and -not (Get-ChildItem $tmp -File)) {
        $src = $dirs[0].FullName
    } else {
        $src = $tmp
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
    Move-Item -Path $src -Destination $dest
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue

    if (-not (Test-Path (Join-Path $dest "source.properties"))) {
        Fail "$($p.Key) extracted but source.properties is missing - unexpected archive"
    }
}

# ------------------------------------------------------------- package.xml
$repoPath = Join-Path $Dl "repository2-3.xml"
if (-not (Test-Path $repoPath)) {
    Say "Fetching repository index..."
    & curl.exe -L --fail --silent --show-error --connect-timeout 30 --max-time 120 -o $repoPath "$Base/repository2-3.xml"
}
$repo = Get-Content $repoPath -Raw

$rootOpen = [regex]::Match($repo, '<(?:[A-Za-z0-9_.]+:)?sdk-repository\b[^>]*>').Value
if (-not $rootOpen) { Fail "Could not find the repository root element" }
$rootName = [regex]::Match($rootOpen, '<([A-Za-z0-9_.:-]+)').Groups[1].Value
$rootClose = "</$rootName>"
$licenseBlock = [regex]::Match($repo, '<license id="android-sdk-license".*?</license>', 'Singleline').Value

foreach ($p in $packages) {
    $esc = [regex]::Escape($p.Key)
    $m = [regex]::Match($repo, '<remotePackage path="' + $esc + '">.*?</remotePackage>', 'Singleline')
    if (-not $m.Success) { Say "WARNING: $($p.Key) not found in repository index"; continue }

    $block = $m.Value
    $block = [regex]::Replace($block, '<archives>.*?</archives>', '', 'Singleline')
    $block = [regex]::Replace($block, '<channelRef[^>]*/>', '', 'Singleline')
    $block = [regex]::Replace($block, '<channelRef>.*?</channelRef>', '', 'Singleline')
    $block = $block -replace '<remotePackage ', '<localPackage ' -replace '</remotePackage>', '</localPackage>'
    $block = $block -replace 'obsolete="true"', 'obsolete="false"'

    $xml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' + "`r`n" +
           $rootOpen + "`r`n" +
           "  " + $licenseBlock + "`r`n" +
           $block + "`r`n" + $rootClose
    $dest = Join-Path $Sdk $p.Dest
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $dest "package.xml"), $xml, $enc)
    Say "Wrote package.xml for $($p.Key)"
}

# ------------------------------------------------------------------- verify
$env:JAVA_HOME = $Jdk
$sdkmanager = Join-Path $Sdk "cmdline-tools\latest\bin\sdkmanager.bat"
if (Test-Path $sdkmanager) {
    Say "Installed packages according to sdkmanager:"
    & $sdkmanager --sdk_root="$Sdk" --list_installed
}

Say "Done."
