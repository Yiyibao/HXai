param([string]$Serial = 'emulator-5554', [switch]$SkipBuild, [switch]$RealModel)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskAdb = Join-Path $taskRoot '.local/android-sdk/platform-tools/adb.exe'
$taskPackage = 'org.mathstudy.agent.debug'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Only the selected emulator may be modified.' }
$taskQa = Join-Path $taskRoot 'qa/s3'
New-Item -ItemType Directory -Force -Path $taskQa | Out-Null
if (-not $SkipBuild) {
    $taskFirebase = Join-Path $taskRoot 'client/app/google-services.json'
    $taskAdded = -not (Test-Path -LiteralPath $taskFirebase)
    if ($taskAdded) { Copy-Item (Join-Path $taskRoot 'qa/s0/google-services.example.json') $taskFirebase }
    try {
        & (Join-Path $taskRoot '.local/gradle-9.6.0/bin/gradle.bat') -p (Join-Path $taskRoot 'client') :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon --console=plain *> (Join-Path $taskQa 'build.log')
        if ($LASTEXITCODE -ne 0) { throw 'S3 build failed; see qa/s3/build.log.' }
    } finally { if ($taskAdded) { Remove-Item -LiteralPath $taskFirebase } }
}
& $taskAdb -s $Serial install -r (Join-Path $taskRoot 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
& $taskAdb -s $Serial install -r (Join-Path $taskRoot 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
function Invoke-S3Test([string]$Classes, [string]$Log, [string[]]$ExtraArguments = @()) {
    & $taskAdb -s $Serial shell am instrument -w -r -e class $Classes @ExtraArguments "$taskPackage.test/androidx.test.runner.AndroidJUnitRunner" *> (Join-Path $taskQa $Log)
    $taskResult = Get-Content -LiteralPath (Join-Path $taskQa $Log) -Raw
    if ($taskResult -notmatch 'OK \(\d+ tests?\)' -or $taskResult -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') { throw "S3 instrumentation failed: $Log" }
}
Invoke-S3Test 'me.rerere.rikkahub.service.LearningLoopPersistenceTest,me.rerere.rikkahub.service.LearningMemoryPersistenceTest,me.rerere.rikkahub.service.LearningMemoryPageTest' 'android.log'
if ($RealModel) {
    Invoke-S3Test 'me.rerere.rikkahub.service.LearningLoopRealModelTest' 'real-model.log' @('-e','realModel','true')
    foreach ($taskFile in @('s3-real-teaching.md', 's3-real-records.json')) {
        & $taskAdb -s $Serial exec-out run-as $taskPackage cat "cache/$taskFile" | Set-Content -LiteralPath (Join-Path $taskQa $taskFile) -Encoding utf8
        if ($LASTEXITCODE -ne 0) { throw "Cannot read evidence $taskFile" }
    }
}
Write-Output 'S3 checks passed. Model transcript still requires mathematical and teaching review.'
