# Builds the Nexus release APK on Windows and leaves the deliverable on the Desktop.
#
# Why this exists: Gradle needs a *JDK* (it compiles a generated version catalog and needs javac),
# not just a JRE. This machine has JAVA_HOME pointed at
# `C:\Program Files\Eclipse Adoptium\jre-17.0.20.101-hotspot`, which is a JRE, so a bare
# `.\gradlew.bat` fails with the very unhelpful:
#
#     org.gradle.api.internal.catalog.GeneratedClassCompilationException:
#     No Java compiler found, please ensure you are running Gradle with a JDK
#
# Rather than depend on the environment being right, this script resolves a JDK itself.
#
# Usage:
#   .\build-release.ps1              # tests + release APK -> Desktop\Nexus-1.0.0.apk
#   .\build-release.ps1 -SkipTests   # faster: skip the unit tests
#   .\build-release.ps1 -NoDesktop   # leave the APK in app/build/outputs only
#   .\build-release.ps1 -Jdk 'C:\path\to\jdk'   # use a specific JDK

[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$NoDesktop,
    [string]$Jdk
)

$ErrorActionPreference = 'Stop'

$repoRoot = $PSScriptRoot
$gradlew  = Join-Path $repoRoot 'gradlew.bat'
if (-not (Test-Path $gradlew)) { throw "gradlew.bat not found next to this script ($repoRoot)." }

# --- Resolve a JDK ------------------------------------------------------------------------------
# A JRE ships java.exe but not javac.exe, so javac is the test that actually matters.
function Test-Jdk([string]$path) {
    if ([string]::IsNullOrWhiteSpace($path)) { return $false }
    return Test-Path (Join-Path $path 'bin\javac.exe')
}

function Resolve-Jdk {
    # 1. Honour an explicit -Jdk argument, then an environment override.
    if ($Jdk) {
        if (Test-Jdk $Jdk) { return $Jdk }
        throw "-Jdk '$Jdk' is not a JDK (no bin\javac.exe)."
    }
    if ($env:NEXUS_JDK -and (Test-Jdk $env:NEXUS_JDK)) { return $env:NEXUS_JDK }

    # 2. Use JAVA_HOME, but only if it is genuinely a JDK.
    if (Test-Jdk $env:JAVA_HOME) { return $env:JAVA_HOME }
    if ($env:JAVA_HOME) {
        Write-Warning "JAVA_HOME is not a JDK (no javac): $env:JAVA_HOME"
    }

    # 3. Fall back to a JDK Gradle already downloaded, then common install locations.
    $candidates = @()
    $gradleJdks = Join-Path $env:USERPROFILE '.gradle\jdks'
    if (Test-Path $gradleJdks) {
        $candidates += Get-ChildItem $gradleJdks -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*REPLACEME*' } |
            Sort-Object Name -Descending |
            ForEach-Object { $_.FullName }
    }
    $candidates += @(
        'C:\Program Files\Java\jdk-21',
        'C:\Program Files\Java\jdk-17',
        'C:\Program Files\Eclipse Adoptium\jdk-21*',
        'C:\Program Files\Eclipse Adoptium\jdk-17*',
        'C:\Program Files\Microsoft\jdk-17*'
    ) | ForEach-Object { Get-Item $_ -ErrorAction SilentlyContinue } | ForEach-Object { $_.FullName }

    foreach ($candidate in $candidates) {
        if (Test-Jdk $candidate) { return $candidate }
    }
    return $null
}

$jdk = Resolve-Jdk
if (-not $jdk) {
    throw @"
No JDK found. Gradle cannot run on a JRE.

Fix it with either of:
  1. Install a JDK 17+ (Temurin/Microsoft/Homebrew-style installer), or
  2. Point this script at one you already have:
       `$env:NEXUS_JDK = 'C:\path\to\jdk'
       .\build-release.ps1

Note: JDK 17, 21 and 25 all work - the project emits Java 17 bytecode. What matters is that
`javac.exe` exists in `<jdk>\bin`.
"@
}

$env:JAVA_HOME = $jdk

# --- Resolve the Android SDK --------------------------------------------------------------------
if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) {
    $sdkCandidates = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        'C:\Android\Sdk',
        '/opt/android-sdk'
    )
    $found = $sdkCandidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'platforms')) } | Select-Object -First 1
    if ($found) {
        $env:ANDROID_HOME = $found
    } elseif (Test-Path (Join-Path $repoRoot 'local.properties')) {
        $sdkLine = Select-String -Path (Join-Path $repoRoot 'local.properties') -Pattern '^sdk\.dir=(.+)$' -ErrorAction SilentlyContinue
        if ($sdkLine) {
            $declared = ($sdkLine.Matches[0].Groups[1].Value -replace '\\:', ':' -replace '\\\\', '\')
            if (Test-Path $declared) { $env:ANDROID_HOME = $declared }
        }
    }
}
if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) {
    throw "Android SDK not found. Set ANDROID_HOME, or write sdk.dir=... into local.properties."
}
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

Write-Host "==> JDK: $env:JAVA_HOME"
Write-Host "==> SDK: $env:ANDROID_HOME"

# --- Build ---------------------------------------------------------------------------------------
$gradleArgs = @('--console=plain')

if (-not $SkipTests) {
    Write-Host "==> Unit tests"
    & $gradlew @gradleArgs ':core:ai:test' ':app:testDebugUnitTest'
    if ($LASTEXITCODE -ne 0) { throw "Unit tests failed." }
}

Write-Host "==> Release APK (R8)"
& $gradlew @gradleArgs ':app:assembleRelease'
if ($LASTEXITCODE -ne 0) { throw "Release build failed." }

$apk = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "Build reported success but $apk is missing." }

if (-not $NoDesktop) {
    $desktop = [Environment]::GetFolderPath('Desktop')
    $target = Join-Path $desktop 'Nexus-1.0.0.apk'
    Copy-Item $apk $target -Force

    # Keep the debug build available as an R8 fallback, matching scripts/build-apk.sh naming.
    $debugApk = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (Test-Path $debugApk) {
        $artifacts = Join-Path $repoRoot 'artifacts'
        New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
        Copy-Item $debugApk (Join-Path $artifacts 'nexus-1.0.0-debug.apk') -Force
    }

    $sizeMb = [math]::Round((Get-Item $target).Length / 1MB, 2)
    Write-Host ""
    Write-Host "==> $target ($sizeMb MB)"
}

Write-Host "==> Done."
