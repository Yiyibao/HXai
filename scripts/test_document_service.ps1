$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskFirebase = Join-Path $taskRoot 'client/app/google-services.json'
$taskPlaceholder = -not (Test-Path -LiteralPath $taskFirebase)
if ($taskPlaceholder) { Copy-Item -LiteralPath (Join-Path $taskRoot 'qa/s0/google-services.example.json') -Destination $taskFirebase }
try {
    & (Join-Path $taskRoot '.local/gradle-9.6.0/bin/gradle.bat') -p (Join-Path $taskRoot 'client') :app:testDebugUnitTest --tests '*DocumentContractTest*' :app:assembleDebug --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Document service Android validation failed' }
} finally {
    if ($taskPlaceholder) { Remove-Item -LiteralPath $taskFirebase }
}
