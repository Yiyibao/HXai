param([string]$Serial = 'emulator-5554', [switch]$SkipBuild, [switch]$RealModel)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskAdb = Join-Path $taskRoot '.local/android-sdk/platform-tools/adb.exe'
$taskPackage = 'org.mathstudy.agent.debug'
if ($Serial -notmatch '^emulator-\d+$') { throw 'This runner only modifies the selected emulator.' }
if (-not $SkipBuild) {
    $taskFirebase = Join-Path $taskRoot 'client/app/google-services.json'
    $taskAddedFirebase = -not (Test-Path -LiteralPath $taskFirebase)
    if ($taskAddedFirebase) { Copy-Item (Join-Path $taskRoot 'qa/s0/google-services.example.json') $taskFirebase }
    try {
        & (Join-Path $taskRoot '.local/gradle-9.6.0/bin/gradle.bat') -p (Join-Path $taskRoot 'client') :app:testDebugUnitTest :ai:testDebugUnitTest :search:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon --console=plain *> (Join-Path $taskRoot '.local/s1-completion-final-build.log')
        if ($LASTEXITCODE -ne 0) { throw 'S1 build failed; see .local/s1-completion-final-build.log.' }
    } finally { if ($taskAddedFirebase) { Remove-Item -LiteralPath $taskFirebase } }
}
$taskApk = Join-Path $taskRoot 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk'
& $taskAdb -s $Serial install -r $taskApk
if ($LASTEXITCODE -ne 0) { throw 'Application install failed.' }
& $taskAdb -s $Serial install -r (Join-Path $taskRoot 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test install failed.' }
function Invoke-S1Instrumentation([string]$TestClass, [string]$LogName, [string[]]$ExtraArguments = @()) {
    $taskLog = Join-Path $taskRoot ".local/$LogName"
    & $taskAdb -s $Serial shell am instrument -w -r -e class $TestClass @ExtraArguments "$taskPackage.test/androidx.test.runner.AndroidJUnitRunner" *> $taskLog
    $taskResult = Get-Content -LiteralPath $taskLog -Raw
    if ($taskResult -notmatch 'OK \(\d+ tests?\)' -or $taskResult -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Instrumentation failed; inspect $taskLog"
    }
}
Invoke-S1Instrumentation 'me.rerere.rikkahub.service.CredentialVaultTest,me.rerere.rikkahub.service.StudyChatCompletionTest,me.rerere.rikkahub.service.StudyNavigationTest,me.rerere.rikkahub.service.ChatNetworkFaultTest' 's1-completion-android-final.log'
& $taskAdb -s $Serial shell am force-stop $taskPackage
Invoke-S1Instrumentation 'me.rerere.rikkahub.service.CredentialVaultTest' 's1-completion-credentials-cold.log'
$taskFixture = [guid]::NewGuid().ToString()
Invoke-S1Instrumentation 'me.rerere.rikkahub.service.RuntimeProcessRecoveryTest' 's1-completion-process-seed.log' @('-e','fixtureId',$taskFixture,'-e','phase','seed')
& $taskAdb -s $Serial shell am force-stop $taskPackage
Invoke-S1Instrumentation 'me.rerere.rikkahub.service.RuntimeProcessRecoveryTest' 's1-completion-process-verify.log' @('-e','fixtureId',$taskFixture,'-e','phase','verify')
if ($RealModel) {
    & $taskAdb -s $Serial shell am force-stop $taskPackage
    Invoke-S1Instrumentation 'me.rerere.rikkahub.service.StudyRealModelTest' 's1-completion-real-model.log' @('-e','realModel','true')
}
Write-Output ('S1 regression passed. APK SHA256: ' + (Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant())
if (-not $RealModel) { Write-Output 'Real model test was not requested; use -RealModel with an existing device connection.' }
