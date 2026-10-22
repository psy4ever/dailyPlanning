$ErrorActionPreference = 'Stop'
$taskRepository = Split-Path -Parent $PSScriptRoot
$taskJava = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
if ($taskJava) { $env:JAVA_HOME = $taskJava }
Push-Location (Join-Path $taskRepository 'server')
try {
    & .\gradlew.bat test bootJar --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Server checks failed' }
} finally { Pop-Location }
$taskBundle = Join-Path $taskRepository 'dist/cloud-release'
New-Item -ItemType Directory -Force -Path (Join-Path $taskBundle 'health/com/dayoudu/cloud') | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRepository 'server/build/libs/daily-rhythm.jar') -Destination $taskBundle
Copy-Item -LiteralPath (Join-Path $taskRepository 'server/build/classes/java/main/com/dayoudu/cloud/HealthProbe.class') -Destination (Join-Path $taskBundle 'health/com/dayoudu/cloud')
Copy-Item -LiteralPath (Join-Path $taskRepository 'server/Dockerfile') -Destination $taskBundle
foreach ($taskFile in @('compose.api.yaml','compose.https.yaml','compose.direct.yaml','Caddyfile','Caddyfile.direct','prepare-api-env.py','prepare-direct.py','start-api.sh','start-direct.sh','backup.py','restore-check.py','daily-rhythm-backup.service','daily-rhythm-backup.timer')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $taskFile) -Destination $taskBundle
}
$taskHashes = Get-ChildItem -LiteralPath $taskBundle -Recurse -File | Where-Object Name -ne 'SHA256SUMS' | ForEach-Object {
    $taskRelative = $_.FullName.Substring($taskBundle.Length + 1).Replace('\','/')
    # Use .NET directly so Windows PowerShell also works when launched from pwsh
    # with an inherited PSModulePath that cannot load its Get-FileHash module.
    $taskHashAlgorithm = [Security.Cryptography.SHA256]::Create()
    $taskHashStream = [IO.File]::OpenRead($_.FullName)
    try {
        $taskHash = [BitConverter]::ToString($taskHashAlgorithm.ComputeHash($taskHashStream)).Replace('-','').ToLowerInvariant()
    } finally {
        $taskHashStream.Dispose()
        $taskHashAlgorithm.Dispose()
    }
    '{0}  {1}' -f $taskHash, $taskRelative
}
[IO.File]::WriteAllText((Join-Path $taskBundle 'SHA256SUMS'), ($taskHashes -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
Write-Output "Deployment bundle: $taskBundle"
