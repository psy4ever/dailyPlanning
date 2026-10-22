param([string]$CloudApiBaseUrl = '')

$ErrorActionPreference = 'Stop'

$taskAndroidDirectory = Join-Path $PSScriptRoot 'android'
if ($CloudApiBaseUrl) {
    $taskCloudOrigin = $CloudApiBaseUrl.Trim().TrimEnd('/')
    if ($taskCloudOrigin -notmatch '^https://[A-Za-z0-9.-]+(:[0-9]+)?$') {
        throw 'CloudApiBaseUrl must be an HTTPS origin such as https://api.your-domain.com (no path).'
    }
    $taskCloudSettings = Join-Path $taskAndroidDirectory 'cloud-api.properties'
    [IO.File]::WriteAllText($taskCloudSettings, "cloudApiBaseUrl=$taskCloudOrigin`n", [Text.Encoding]::ASCII)
}
Push-Location -LiteralPath $taskAndroidDirectory
try {
    & .\gradlew.bat :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw 'APK build failed. See the error above.'
    }
} finally {
    Pop-Location
}

$taskApkSource = Join-Path $taskAndroidDirectory 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path -LiteralPath $taskApkSource -PathType Leaf)) {
    throw 'Build finished without the expected APK.'
}
$taskApkDirectory = Join-Path $PSScriptRoot 'dist/apk'
New-Item -ItemType Directory -Force -Path $taskApkDirectory | Out-Null
$taskApkOutput = Join-Path $taskApkDirectory 'dayoudu-debug.apk'
Copy-Item -LiteralPath $taskApkSource -Destination $taskApkOutput -Force
Write-Output ''
Write-Output "APK created: $taskApkOutput"
