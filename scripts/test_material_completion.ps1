param([string]$Serial = 'emulator-5554', [switch]$SkipBuild, [switch]$RealModel, [switch]$Delivery)
$ErrorActionPreference = 'Stop'
$materialRoot = Split-Path $PSScriptRoot -Parent
$materialAdb = Join-Path $materialRoot '.local/android-sdk/platform-tools/adb.exe'
$materialQa = Join-Path $materialRoot 'qa/material'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Only the selected emulator may be modified.' }
New-Item -ItemType Directory -Force $materialQa | Out-Null
if (-not $SkipBuild) {
    $materialFirebase = Join-Path $materialRoot 'client/app/google-services.json'
    $materialAdded = -not (Test-Path -LiteralPath $materialFirebase)
    if ($materialAdded) { Copy-Item (Join-Path $materialRoot 'qa/s0/google-services.example.json') $materialFirebase }
    try {
        & (Join-Path $materialRoot '.local/gradle-9.6.0/bin/gradle.bat') -p (Join-Path $materialRoot 'client') :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon --console=plain *> (Join-Path $materialQa 'build-final.log')
        if ($LASTEXITCODE -ne 0) { throw 'Build failed; see qa/material/build-final.log.' }
    } finally { if ($materialAdded) { Remove-Item -LiteralPath $materialFirebase } }
}
& $materialAdb -s $Serial install -r (Join-Path $materialRoot 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'App install failed.' }
& $materialAdb -s $Serial install -r (Join-Path $materialRoot 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test install failed.' }
function Invoke-MaterialTest([string]$Classes, [string]$Log, [string[]]$ExtraArguments = @()) {
    & $materialAdb -s $Serial shell am instrument -w -r -e class $Classes @ExtraArguments 'org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner' *> (Join-Path $materialQa $Log)
    $materialResult = Get-Content -LiteralPath (Join-Path $materialQa $Log) -Raw
    if ($materialResult -notmatch 'OK \(\d+ tests?\)' -or $materialResult -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') { throw "Instrumentation failed: $Log" }
}
Invoke-MaterialTest 'me.rerere.rikkahub.service.MaterialFilesTest,me.rerere.rikkahub.service.MaterialPageTest,me.rerere.rikkahub.service.LearningLoopPersistenceTest,me.rerere.rikkahub.service.CredentialVaultTest' 'android-final.log'
Invoke-MaterialTest 'me.rerere.rikkahub.service.MaterialFidelityTest,me.rerere.rikkahub.service.MaterialPaginationTest' 'fidelity-android.log'
if ($Delivery) {
    & (Join-Path $PSScriptRoot 'build_material_receiver.ps1')
    Invoke-MaterialTest 'me.rerere.rikkahub.service.MaterialDeliveryTest' 'delivery-android.log'
}
if ($RealModel) { Invoke-MaterialTest 'me.rerere.rikkahub.service.MaterialRealModelTest' 'real-model.log' @('-e', 'realModel', 'true') }
Write-Output 'Local checks completed; image generation configuration/result and external rendered files must be reviewed separately.'
