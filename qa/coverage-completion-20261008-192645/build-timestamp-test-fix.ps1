$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$configuration = "$root/client/app/google-services.json"
$created = $false
try {
    if (-not (Test-Path -LiteralPath $configuration)) {
        Copy-Item -LiteralPath "$root/qa/s0/google-services.example.json" -Destination $configuration
        $created = $true
    }
    & "$root/.local/gradle-9.6.0/bin/gradle.bat" -p "$root/client" :app:assembleDebugAndroidTest :app:lintDebug --offline --no-daemon --console=plain *> "$PSScriptRoot/build-timestamp-test-fix.log"
    $LASTEXITCODE | Set-Content "$PSScriptRoot/build-timestamp-test-fix-exit.txt"
} finally {
    if ($created) { Remove-Item -LiteralPath $configuration }
}

