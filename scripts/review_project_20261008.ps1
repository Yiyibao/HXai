param([switch]$SkipInstall, [string]$OutputDirectory = 'qa/full-review-20261008')
$ErrorActionPreference = 'Stop'
$reviewRoot = Split-Path $PSScriptRoot -Parent
$reviewAdb = Join-Path $reviewRoot '.local/android-sdk/platform-tools/adb.exe'
$reviewQa = Join-Path $reviewRoot $OutputDirectory
New-Item -ItemType Directory -Path $reviewQa -Force | Out-Null
$reviewResults = [System.Collections.Generic.List[object]]::new()
if (-not $SkipInstall) {
    & $reviewAdb -s emulator-5554 install -r (Join-Path $reviewRoot 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk')
    if ($LASTEXITCODE -ne 0) { throw 'App installation failed' }
    & $reviewAdb -s emulator-5554 install -r (Join-Path $reviewRoot 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Test installation failed' }
}
function Invoke-ReviewCase([string]$Name, [string]$Classes, [string[]]$Extra = @()) {
    $reviewStart = Get-Date
    $reviewLog = Join-Path $reviewQa "$Name.log"
    & $reviewAdb -s emulator-5554 shell am instrument -w -r -e class $Classes @Extra org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> $reviewLog
    $reviewOutput = Get-Content -LiteralPath $reviewLog -Raw
    $reviewPass = $reviewOutput -match 'OK \(\d+ tests?\)' -and $reviewOutput -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'
    $reviewResults.Add([pscustomobject]@{ name=$Name; passed=$reviewPass; started=$reviewStart.ToString('o'); seconds=[math]::Round(((Get-Date)-$reviewStart).TotalSeconds, 1); log="$Name.log" })
    $reviewResults | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $reviewQa 'emulator-results.json') -Encoding utf8
    Write-Output "$Name : $reviewPass"
}
$reviewPrefix = 'me.rerere.rikkahub.service.'
foreach ($reviewClass in @('StudyNavigationTest','StudyChatCompletionTest','ChatNetworkFaultTest','CredentialVaultTest','LearningMemoryPersistenceTest','LearningMemoryPageTest','LegacyMemoryEntryTest','LearningLoopPersistenceTest','MaterialFilesTest','MaterialFidelityTest','MaterialPageTest','MaterialPaginationTest','MaterialDeliveryTest','DocumentServicePageTest','BackupReliabilityTest','BackupPageTest','DiagnosticsTest','DiagnosticsPageTest','FrontendModelPickerTest','FrontendDeliveryRoutesTest','ProjectReviewAuditTest','ProjectReviewDeliveryTest')) {
    Invoke-ReviewCase $reviewClass ($reviewPrefix + $reviewClass)
}
$reviewFixture = [guid]::NewGuid().ToString()
Invoke-ReviewCase 'runtime-seed' ($reviewPrefix + 'RuntimeProcessRecoveryTest') @('-e','fixtureId',$reviewFixture,'-e','phase','seed')
& $reviewAdb -s emulator-5554 shell am force-stop org.mathstudy.agent.debug
Invoke-ReviewCase 'runtime-verify' ($reviewPrefix + 'RuntimeProcessRecoveryTest') @('-e','fixtureId',$reviewFixture,'-e','phase','verify')
$reviewMemoryFixture = [guid]::NewGuid().ToString()
Invoke-ReviewCase 'memory-seed' ($reviewPrefix + 'LearningMemoryPersistenceTest#memorySurvivesProcessRestart') @('-e','memoryFixture',$reviewMemoryFixture,'-e','memoryPhase','seed')
& $reviewAdb -s emulator-5554 shell am force-stop org.mathstudy.agent.debug
Invoke-ReviewCase 'memory-verify' ($reviewPrefix + 'LearningMemoryPersistenceTest#memorySurvivesProcessRestart') @('-e','memoryFixture',$reviewMemoryFixture,'-e','memoryPhase','verify')
Write-Output 'Review run completed; inspect all logs including skipped tests and failures.'
