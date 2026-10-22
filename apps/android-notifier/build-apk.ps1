<#
    Bonjee Notifier - build the debug APK without Android Studio.

    Usage:
      powershell -ExecutionPolicy Bypass -File build-apk.ps1
      powershell -ExecutionPolicy Bypass -File build-apk.ps1 -Release

    Output:
      app\build\outputs\apk\debug\app-debug.apk
      app\build\outputs\apk\release\app-release.apk
#>

param(
    [switch]$Release,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$Root      = Split-Path -Parent $MyInvocation.MyCommand.Path
$Toolchain = Join-Path $Root ".toolchain"
$Jdk       = Join-Path $Toolchain "jdk"
$Sdk       = Join-Path $Toolchain "android-sdk"

if (-not (Test-Path (Join-Path $Jdk "bin\java.exe"))) {
    Write-Host "[build] Toolchain missing. Running setup-toolchain.ps1 first..." -ForegroundColor Yellow
    & (Join-Path $Root "setup-toolchain.ps1")
}

$env:JAVA_HOME       = $Jdk
$env:ANDROID_HOME    = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:PATH            = "$Jdk\bin;$Sdk\platform-tools;$env:PATH"

$sdkDir = $Sdk.Replace("\", "/").Replace(":", "\:")
Set-Content -Path (Join-Path $Root "local.properties") -Value "sdk.dir=$sdkDir" -Encoding ASCII

$gradlew = Join-Path $Root "gradlew.bat"
$localGradle = Join-Path $Toolchain "gradle-8.7\bin\gradle.bat"

if (-not (Test-Path $gradlew) -and -not (Test-Path $localGradle)) {
    Write-Host "[build] Gradle wrapper missing. Generating it..." -ForegroundColor Yellow
    $gradleHome = Join-Path $Toolchain "gradle-8.7"
    Push-Location $Root
    & (Join-Path $gradleHome "bin\gradle.bat") wrapper --gradle-version 8.7 --distribution-type bin
    Pop-Location
}

# Prefer the already-extracted local Gradle: the wrapper would download
# another 134 MB distribution into ~/.gradle for no reason.
if (Test-Path $localGradle) {
    $runner = $localGradle
    Write-Host "[build] Using bundled Gradle: $localGradle" -ForegroundColor Cyan
} else {
    $runner = $gradlew
}

$task = if ($Release) { "assembleRelease" } else { "assembleDebug" }
$args = @($task, "--stacktrace")
if ($Clean) { $args = @("clean") + $args }

Write-Host "[build] Running: $task" -ForegroundColor Cyan
Push-Location $Root
& $runner @args
$code = $LASTEXITCODE
Pop-Location

if ($code -ne 0) {
    Write-Host "[build] FAILED with exit code $code" -ForegroundColor Red
    exit $code
}

$apk = if ($Release) {
    Join-Path $Root "app\build\outputs\apk\release\app-release.apk"
} else {
    Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
}

if (Test-Path $apk) {
    $size = [math]::Round((Get-Item $apk).Length / 1MB, 2)
    $friendly = Join-Path $Root "BonjeeNotifier.apk"
    Copy-Item -Path $apk -Destination $friendly -Force
    Write-Host ""
    Write-Host "[build] SUCCESS" -ForegroundColor Green
    Write-Host "[build] APK: $apk ($size MB)" -ForegroundColor Green
    Write-Host "[build] Copy to send to your phone: $friendly" -ForegroundColor Green
    Write-Host "[build] Transfer it to the phone (USB, Google Drive, or send it to yourself)," -ForegroundColor Green
    Write-Host "[build] then tap it on the phone and allow 'install unknown apps'." -ForegroundColor Green
    try { Start-Process explorer.exe -ArgumentList "/select,`"$friendly`"" } catch { }
} else {
    Write-Host "[build] Finished but APK not found at $apk" -ForegroundColor Yellow
    exit 1
}
