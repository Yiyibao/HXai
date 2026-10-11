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
        & (Join-Path $taskRoot '.local/gradle-9.6.0/bin/gradle.bat') -p (Join-Path $taskRoot 'client') :app:testDebugUnitTest :ai:testDebugUnitTest :search:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon --console=plain *> (Join-Path $taskRoot '.local/s2-final-build.log')
        if ($LASTEXITCODE -ne 0) { throw 'S2 build failed; see .local/s2-final-build.log.' }
    } finally { if ($taskAddedFirebase) { Remove-Item -LiteralPath $taskFirebase } }
}
& $taskAdb -s $Serial install -r (Join-Path $taskRoot 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'Application install failed.' }
& $taskAdb -s $Serial install -r (Join-Path $taskRoot 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test install failed.' }
function Invoke-S2Instrumentation([string]$TestClass, [string]$LogName, [string[]]$ExtraArguments = @()) {
    $taskLog = Join-Path $taskRoot ".local/$LogName"
    & $taskAdb -s $Serial shell am instrument -w -r -e class $TestClass @ExtraArguments "$taskPackage.test/androidx.test.runner.AndroidJUnitRunner" *> $taskLog
    $taskResult = Get-Content -LiteralPath $taskLog -Raw
    if ($taskResult -notmatch 'OK \(\d+ tests?\)' -or $taskResult -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') { throw "Instrumentation failed; inspect $taskLog" }
}
Invoke-S2Instrumentation 'me.rerere.rikkahub.service.LearningMemoryPersistenceTest,me.rerere.rikkahub.service.LearningMemoryPageTest,me.rerere.rikkahub.service.LegacyMemoryEntryTest,me.rerere.rikkahub.service.StudyNavigationTest,me.rerere.rikkahub.service.StudyChatCompletionTest,me.rerere.rikkahub.service.ChatNetworkFaultTest,me.rerere.rikkahub.service.CredentialVaultTest' 's2-android-final.log'
$taskFixture = [guid]::NewGuid().ToString()
Invoke-S2Instrumentation 'me.rerere.rikkahub.service.LearningMemoryPersistenceTest#memorySurvivesProcessRestart' 's2-process-seed.log' @('-e','memoryFixture',$taskFixture,'-e','memoryPhase','seed')
& $taskAdb -s $Serial shell am force-stop $taskPackage
Invoke-S2Instrumentation 'me.rerere.rikkahub.service.LearningMemoryPersistenceTest#memorySurvivesProcessRestart' 's2-process-verify.log' @('-e','memoryFixture',$taskFixture,'-e','memoryPhase','verify')
if ($RealModel) {
    Invoke-S2Instrumentation 'me.rerere.rikkahub.service.LearningMemoryRealModelTest' 's2-real-final.log' @('-e','realModel','true')
}
Write-Output 'S2 regression passed.'
