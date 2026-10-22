<#
    Bonjee Notifier - toolchain bootstrap (command line only, no Android Studio)

    Downloads / extracts:
      * JDK 17 (Temurin)          -> .toolchain\jdk
      * Android SDK cmdline-tools -> .toolchain\android-sdk
      * Gradle 8.7                -> .toolchain\gradle-8.7

    Then installs: platform-tools, platforms;android-34, build-tools;34.0.0
    Then writes local.properties and generates the Gradle wrapper.

    Usage:  powershell -ExecutionPolicy Bypass -File setup-toolchain.ps1
#>

$ErrorActionPreference = "Stop"

$Root       = Split-Path -Parent $MyInvocation.MyCommand.Path
$Toolchain  = Join-Path $Root ".toolchain"
$Downloads  = Join-Path $Toolchain "downloads"
$Sdk        = Join-Path $Toolchain "android-sdk"
$Jdk        = Join-Path $Toolchain "jdk"
$CmdTools   = Join-Path $Sdk "cmdline-tools\latest"
$GradleHome = Join-Path $Toolchain "gradle-8.7"

function Say($m) { Write-Host "[setup] $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "[setup] ERROR: $m" -ForegroundColor Red; exit 1 }

New-Item -ItemType Directory -Force -Path $Toolchain, $Downloads | Out-Null

# ------------------------------------------------------------------ downloads
$assets = @(
    @{ Name = "jdk17.zip";    Url = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" },
    @{ Name = "cmdtools.zip"; Url = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" },
    @{ Name = "gradle.zip";   Url = "https://services.gradle.org/distributions/gradle-8.7-bin.zip" }
)

foreach ($a in $assets) {
    $target = Join-Path $Downloads $a.Name
    if (Test-Path $target) {
        Say "$($a.Name) already downloaded ($([math]::Round((Get-Item $target).Length/1MB,1)) MB)"
    } else {
        Say "Downloading $($a.Name) ..."
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $a.Url -OutFile $target -UseBasicParsing -TimeoutSec 1800
        Say "$($a.Name) done ($([math]::Round((Get-Item $target).Length/1MB,1)) MB)"
    }
}

# ------------------------------------------------------------------------- JDK
if (-not (Test-Path (Join-Path $Jdk "bin\java.exe"))) {
    Say "Extracting JDK 17..."
    if (Test-Path $Jdk) { Remove-Item -Recurse -Force $Jdk }
    $tmp = Join-Path $Toolchain "jdk-tmp"
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path (Join-Path $Downloads "jdk17.zip") -DestinationPath $tmp -Force
    $inner = Get-ChildItem -Path $tmp -Directory | Select-Object -First 1
    if (-not $inner) { Fail "JDK archive did not contain a folder" }
    New-Item -ItemType Directory -Force -Path $Jdk | Out-Null
    Copy-Item -Path (Join-Path $inner.FullName "*") -Destination $Jdk -Recurse -Force
    Remove-Item -Recurse -Force $tmp
}
$env:JAVA_HOME = $Jdk
Say "JAVA_HOME = $Jdk"

# ------------------------------------------------------------------ cmdline tools
if (-not (Test-Path (Join-Path $CmdTools "bin\sdkmanager.bat"))) {
    Say "Extracting Android command line tools..."
    $tmp = Join-Path $Toolchain "cmd-tmp"
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path (Join-Path $Downloads "cmdtools.zip") -DestinationPath $tmp -Force
    $inner = Join-Path $tmp "cmdline-tools"
    if (-not (Test-Path $inner)) { Fail "cmdline-tools archive layout unexpected" }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $CmdTools) | Out-Null
    if (Test-Path $CmdTools) { Remove-Item -Recurse -Force $CmdTools }
    Move-Item -Path $inner -Destination $CmdTools
    Remove-Item -Recurse -Force $tmp
}

$env:ANDROID_HOME      = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$sdkmanager = Join-Path $CmdTools "bin\sdkmanager.bat"

Say "Accepting SDK licences..."
$yes = (1..40 | ForEach-Object { "y" })
$yes | & $sdkmanager --sdk_root="$Sdk" --licenses | Out-Null

Say "Installing platform-tools, platforms;android-34, build-tools;34.0.0 ..."
& $sdkmanager --sdk_root="$Sdk" "platform-tools" "platforms;android-34" "build-tools;34.0.0" | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "sdkmanager exited with $LASTEXITCODE" }
Say "SDK packages installed."

# ----------------------------------------------------------------------- gradle
if (-not (Test-Path (Join-Path $GradleHome "bin\gradle.bat"))) {
    Say "Extracting Gradle 8.7..."
    Expand-Archive -Path (Join-Path $Downloads "gradle.zip") -DestinationPath $Toolchain -Force
}
if (-not (Test-Path (Join-Path $GradleHome "bin\gradle.bat"))) { Fail "Gradle not found at $GradleHome" }
Say "Gradle ready at $GradleHome"

# -------------------------------------------------------------- local.properties
$sdkDir = $Sdk.Replace("\", "/").Replace(":", "\:")
$localProps = Join-Path $Root "local.properties"
Set-Content -Path $localProps -Value "sdk.dir=$sdkDir" -Encoding ASCII
Say "Wrote local.properties"

# ---------------------------------------------------------------------- wrapper
if (-not (Test-Path (Join-Path $Root "gradlew.bat"))) {
    Say "Generating Gradle wrapper (gradlew / gradlew.bat)..."
    Push-Location $Root
    & (Join-Path $GradleHome "bin\gradle.bat") wrapper --gradle-version 8.7 --distribution-type bin
    Pop-Location
}

Write-Host ""
Write-Host "==============================================" -ForegroundColor Green
Write-Host " Toolchain ready. Now run:  .\build-apk.ps1" -ForegroundColor Green
Write-Host "==============================================" -ForegroundColor Green
