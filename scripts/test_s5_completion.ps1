param([string]$Serial = 'emulator-5554', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
if ($Serial -notmatch '^emulator-\d+$') { throw 'Only the selected development emulator may be modified.' }
$taskAdb = Join-Path $taskRoot '.local/android-sdk/platform-tools/adb.exe'
if (-not $SkipBuild) {
    $taskFirebase = Join-Path $taskRoot 'client/app/google-services.json'
    $taskAdded = -not (Test-Path -LiteralPath $taskFirebase)
    if ($taskAdded) { Copy-Item -LiteralPath (Join-Path $taskRoot 'qa/s0/google-services.example.json') -Destination $taskFirebase }
    try {
        & (Join-Path $taskRoot '.local/gradle-9.6.0/bin/gradle.bat') -p (Join-Path $taskRoot 'client') :app:testDebugUnitTest :ai:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon --console=plain *> (Join-Path $taskRoot '.local/s5-completion-build.log')
        if ($LASTEXITCODE -ne 0) { throw 'Build failed; see .local/s5-completion-build.log.' }
    } finally { if ($taskAdded) { Remove-Item -LiteralPath $taskFirebase } }
}
& $taskAdb -s $Serial install -r (Join-Path $taskRoot 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'Development APK install failed.' }
& $taskAdb -s $Serial install -r (Join-Path $taskRoot 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test APK install failed.' }
function Invoke-S5Test([string]$Classes, [string]$Log, [string[]]$Extra = @()) {
    $taskLog = Join-Path $taskRoot ".local/$Log"
    & $taskAdb -s $Serial shell am instrument -w -r -e class $Classes @Extra org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> $taskLog
    $taskOutput = Get-Content -LiteralPath $taskLog -Raw
    if ($taskOutput -notmatch 'OK \(\d+ tests?\)' -or $taskOutput -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') { throw "Tests failed; see $taskLog" }
    Write-Output ($taskOutput | Select-String 'OK \(\d+ tests?\)' | ForEach-Object { $_.Matches.Value })
}
Invoke-S5Test 'me.rerere.rikkahub.service.BackupReliabilityTest,me.rerere.rikkahub.service.BackupPageTest' 's5-backup-android.log'
Invoke-S5Test 'me.rerere.rikkahub.service.LearningMemoryPersistenceTest,me.rerere.rikkahub.service.LearningLoopPersistenceTest,me.rerere.rikkahub.service.MaterialFilesTest,me.rerere.rikkahub.service.ChatNetworkFaultTest,me.rerere.rikkahub.service.CredentialVaultTest' 's5-regression-android.log'
$taskFixture = [guid]::NewGuid().ToString()
Invoke-S5Test 'me.rerere.rikkahub.service.LearningMemoryPersistenceTest#memorySurvivesProcessRestart' 's5-process-seed.log' @('-e','memoryFixture',$taskFixture,'-e','memoryPhase','seed')
& $taskAdb -s $Serial shell am force-stop org.mathstudy.agent.debug
Invoke-S5Test 'me.rerere.rikkahub.service.LearningMemoryPersistenceTest#memorySurvivesProcessRestart' 's5-process-verify.log' @('-e','memoryFixture',$taskFixture,'-e','memoryPhase','verify')
Write-Output 'S5 emulator verification passed.'
