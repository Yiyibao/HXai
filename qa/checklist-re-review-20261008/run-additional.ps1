$ErrorActionPreference = 'Stop'
$rrRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$rrAdb = Join-Path $rrRoot '.local/android-sdk/platform-tools/adb.exe'
$rrResults = [System.Collections.Generic.List[object]]::new()
function Invoke-ReReview([string]$Name, [string]$Class, [string[]]$Extra = @()) {
    $rrStart = Get-Date
    $rrLog = Join-Path $PSScriptRoot "$Name.log"
    & $rrAdb -s emulator-5554 shell am instrument -w -r -e class "me.rerere.rikkahub.service.$Class" @Extra org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> $rrLog
    $rrText = Get-Content -Raw -LiteralPath $rrLog
    $rrPass = $rrText -match 'OK \(\d+ tests?\)' -and $rrText -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'
    $rrResults.Add([pscustomobject]@{name=$Name;passed=$rrPass;started=$rrStart.ToString('o');seconds=[math]::Round(((Get-Date)-$rrStart).TotalSeconds,1);log="$Name.log"})
    $rrResults | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $PSScriptRoot 'additional-results.json')
    Write-Output "$Name : $rrPass"
}
# Boundary runner already executes the overlapping tests. Run each remaining suite once.
foreach ($rrClass in @('StudyNavigationTest','CredentialVaultTest','LearningMemoryPageTest','LegacyMemoryEntryTest','LearningLoopPersistenceTest','MaterialPaginationTest','DocumentServicePageTest','BackupPageTest','DiagnosticsTest','DiagnosticsPageTest','FrontendModelPickerTest','FrontendDeliveryRoutesTest','WarningFixGalleryTest','WarningFixSliderTest','WarningReviewBoundaryTest')) {
    Invoke-ReReview $rrClass $rrClass
}
$rrMemory = [guid]::NewGuid().ToString()
Invoke-ReReview 'memory-seed' 'LearningMemoryPersistenceTest#memorySurvivesProcessRestart' @('-e','memoryFixture',$rrMemory,'-e','memoryPhase','seed')
& $rrAdb -s emulator-5554 shell am force-stop org.mathstudy.agent.debug
Invoke-ReReview 'memory-verify' 'LearningMemoryPersistenceTest#memorySurvivesProcessRestart' @('-e','memoryFixture',$rrMemory,'-e','memoryPhase','verify')
$rrFeedback = [guid]::NewGuid().ToString()
Invoke-ReReview 'feedback-seed' 'FrontendFeedbackProcessTest' @('-e','feedbackFixture',$rrFeedback,'-e','feedbackPhase','seed')
& $rrAdb -s emulator-5554 shell am force-stop org.mathstudy.agent.debug
Invoke-ReReview 'feedback-verify' 'FrontendFeedbackProcessTest' @('-e','feedbackFixture',$rrFeedback,'-e','feedbackPhase','verify')
# Existing authorized configuration; fixtures isolate synthetic owners and restore settings.
# Each remote test is invoked once. A remote rejection is retained without automatic retry.
foreach ($rrClass in @('StudyRealModelTest','ProjectReviewTeachingTest','LearningLoopRealModelTest','LearningMemoryRealModelTest','MaterialRealModelTest')) {
    Invoke-ReReview $rrClass $rrClass @('-e','realModel','true')
}
