param([string]$Batch, [string[]]$Tasks = @(':app:assembleDebug', ':app:assembleDebugAndroidTest'))
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$configuration = "$root/client/app/google-services.json"
$created = $false
try {
    if (-not (Test-Path -LiteralPath $configuration)) {
        Copy-Item -LiteralPath "$root/qa/s0/google-services.example.json" -Destination $configuration
        $created = $true
    }
    & "$root/.local/gradle-9.6.0/bin/gradle.bat" -p "$root/client" -I "$PSScriptRoot/probes.init.gradle" @Tasks --offline --no-daemon --no-configuration-cache --console=plain *> "$PSScriptRoot/$Batch.log"
    $code = $LASTEXITCODE
    $code | Set-Content "$PSScriptRoot/$Batch-exit.txt"
    exit $code
} finally {
    if ($created) { Remove-Item -LiteralPath $configuration }
}
