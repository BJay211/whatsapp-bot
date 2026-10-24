# Bonjee Bot - live Android build runner.
#
# Runs the toolchain restore (if needed), then the APK build, writing every
# line to build-live.log so progress can be watched from another terminal:
#
#   Get-Content .\build-live.log -Wait -Tail 40
#
# When the APK exists it is copied to .\JARVIS.apk and Explorer is opened with
# the file selected.

$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Log  = Join-Path $Root "build-live.log"

function Log($m) {
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m
    Write-Host $line
    Add-Content -Path $Log -Value $line -Encoding UTF8
}

Set-Content -Path $Log -Value "" -Encoding UTF8
Log "=== BUILD START ==="

$Jdk       = Join-Path $Root ".toolchain\jdk"
$Gradle    = Join-Path $Root ".toolchain\gradle-8.7\bin\gradle.bat"
$AndroidJar = Join-Path $Root ".toolchain\android-sdk\platforms\android-34\android.jar"
$Aapt2     = Join-Path $Root ".toolchain\android-sdk\build-tools\34.0.0\aapt2.exe"

$needToolchain = (-not (Test-Path (Join-Path $Jdk "bin\java.exe"))) -or
                 (-not (Test-Path $Gradle)) -or
                 (-not (Test-Path $AndroidJar)) -or
                 (-not (Test-Path $Aapt2))

if ($needToolchain) {
    Log "PHASE 1/2  Toolchain incomplete - restoring JDK + Gradle + Android SDK"
    Log "Watch this log for live MB/s progress. Downloads are resumable."
    & powershell -ExecutionPolicy Bypass -NoProfile -File (Join-Path $Root "restore-toolchain.ps1") 2>&1 |
        ForEach-Object { $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $_; Write-Host $line; Add-Content -Path $Log -Value $line -Encoding UTF8 }

    if (-not (Test-Path (Join-Path $Jdk "bin\java.exe"))) {
        Log "restore-toolchain did not produce a JDK - falling back to setup-toolchain.ps1"
        & powershell -ExecutionPolicy Bypass -NoProfile -File (Join-Path $Root "setup-toolchain.ps1") 2>&1 |
            ForEach-Object { $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $_; Write-Host $line; Add-Content -Path $Log -Value $line -Encoding UTF8 }
    }
} else {
    Log "PHASE 1/2  Toolchain already present - skipping downloads"
}

Log "PHASE 2/2  Compiling APK (assembleDebug)"

$env:JAVA_HOME        = $Jdk
$env:ANDROID_HOME     = Join-Path $Root ".toolchain\android-sdk"
$env:ANDROID_SDK_ROOT = Join-Path $Root ".toolchain\android-sdk"
$env:PATH             = "$Jdk\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

$runner = if (Test-Path $Gradle) { $Gradle } else { Join-Path $Root "gradlew.bat" }

Push-Location $Root
& $runner assembleDebug --console=plain 2>&1 |
    ForEach-Object { $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $_; Write-Host $line; Add-Content -Path $Log -Value $line -Encoding UTF8 }
$code = $LASTEXITCODE
Pop-Location

$apk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
if ($code -ne 0 -or -not (Test-Path $apk)) {
    Log "BUILD FAILED (gradle exit $code). See the log above for the first error."
    Log "=== BUILD END (FAILED) ==="
    exit 1
}

$sizeMb = [math]::Round((Get-Item $apk).Length / 1MB, 2)
$friendly = Join-Path $Root "JARVIS.apk"
Copy-Item -Path $apk -Destination $friendly -Force

Log "BUILD SUCCESS"
Log ("APK: {0} ({1} MB)" -f $apk, $sizeMb)
Log ("Copy for your phone: {0}" -f $friendly)
Log "=== BUILD END (SUCCESS) ==="

try { Start-Process explorer.exe -ArgumentList "/select,`"$friendly`"" } catch { }
