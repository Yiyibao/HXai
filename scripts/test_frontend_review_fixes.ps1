param([switch]$BuildAndroidTests)
$ErrorActionPreference = 'Stop'
$frontendRoot = Split-Path $PSScriptRoot -Parent
$frontendFirebase = Join-Path $frontendRoot 'client/app/google-services.json'
$frontendCreatedPlaceholder = -not (Test-Path -LiteralPath $frontendFirebase)
if ($frontendCreatedPlaceholder) {
    Copy-Item -LiteralPath (Join-Path $frontendRoot 'qa/s0/google-services.example.json') -Destination $frontendFirebase
}
try {
    $frontendArguments = @('-p', (Join-Path $frontendRoot 'client'), ':app:testDebugUnitTest',
        '--tests', '*StudyChatContractTest*', '--tests', '*MemoryContractTest*', '--tests', '*DocumentContractTest*',
        ':app:assembleDebug')
    if ($BuildAndroidTests) { $frontendArguments += ':app:assembleDebugAndroidTest' }
    $frontendArguments += @('--no-daemon', '--console=plain')
    & (Join-Path $frontendRoot '.local/gradle-9.6.0/bin/gradle.bat') @frontendArguments
    if ($LASTEXITCODE -ne 0) { throw "Frontend review validation failed: $LASTEXITCODE" }
} finally {
    if ($frontendCreatedPlaceholder) { Remove-Item -LiteralPath $frontendFirebase }
}
