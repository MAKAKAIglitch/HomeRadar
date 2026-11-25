param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'
$GradleVersion = '9.5.0'
$DistributionUrl = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
$CacheRoot = Join-Path $env:LOCALAPPDATA 'HomeRadar\Gradle'
$GradleHome = Join-Path $CacheRoot $GradleVersion
$GradleExe = Join-Path $GradleHome 'bin\gradle.bat'

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'JDK 17 is required. Install JDK 17 and try again.'
}

$SdkRoot = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($SdkRoot)) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
    $DefaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $DefaultSdk) { $SdkRoot = $DefaultSdk }
}
if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
    throw 'Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT.'
}
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot

if (-not (Test-Path (Join-Path $SdkRoot 'platforms\android-37\android.jar'))) {
    throw "Android SDK Platform 37 is missing. Install it with sdkmanager 'platforms;android-37'."
}

$SystemGradle = Get-Command gradle -ErrorAction SilentlyContinue
$UseSystemGradle = $false
if ($SystemGradle) {
    $VersionText = (& $SystemGradle.Source --version 2>&1 | Out-String)
    if ($VersionText -match "(?m)^Gradle $([regex]::Escape($GradleVersion))\s*$") {
        $GradleExe = $SystemGradle.Source
        $UseSystemGradle = $true
    }
}

if (-not $UseSystemGradle -and -not (Test-Path $GradleExe)) {
    New-Item -ItemType Directory -Force -Path $CacheRoot | Out-Null
    $TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("homeradar-gradle-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $TempRoot | Out-Null
    try {
        $ZipPath = Join-Path $TempRoot 'gradle.zip'
        $HashPath = Join-Path $TempRoot 'gradle.zip.sha256'
        Write-Host "Downloading Gradle $GradleVersion (binary distribution)…"
        Invoke-WebRequest -UseBasicParsing -Uri $DistributionUrl -OutFile $ZipPath
        Invoke-WebRequest -UseBasicParsing -Uri "$DistributionUrl.sha256" -OutFile $HashPath

        $ExpectedHash = (Get-Content $HashPath -Raw).Trim().Split(' ')[0].ToLowerInvariant()
        $ActualHash = (Get-FileHash -Path $ZipPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($ExpectedHash) -or $ActualHash -ne $ExpectedHash) {
            throw 'Gradle distribution SHA-256 verification failed.'
        }

        Expand-Archive -Path $ZipPath -DestinationPath $TempRoot -Force
        if (Test-Path $GradleHome) { Remove-Item $GradleHome -Recurse -Force }
        Move-Item (Join-Path $TempRoot "gradle-$GradleVersion") $GradleHome
    }
    finally {
        if (Test-Path $TempRoot) { Remove-Item $TempRoot -Recurse -Force }
    }
}

$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $RootDir
try {
    Write-Host "Building HomeRadar debug APK with Gradle $GradleVersion…"
    & $GradleExe --no-daemon --max-workers=2 --build-cache --console=plain :android:app:assembleDebug @GradleArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE."
    }

    $ApkPath = Join-Path $RootDir 'android\app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $ApkPath) -or (Get-Item $ApkPath).Length -eq 0) {
        throw "Build finished but APK was not found: $ApkPath"
    }
    Write-Host ''
    Write-Host 'Build successful:'
    Write-Host $ApkPath
}
finally {
    Pop-Location
}
