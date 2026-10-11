param([string]$OutputDirectory = 'qa/warnings-review-20261008')
$ErrorActionPreference = 'Stop'
$warningRoot = Split-Path $PSScriptRoot -Parent
$warningQa = Join-Path $warningRoot $OutputDirectory
$warningAdb = Join-Path $warningRoot '.local/android-sdk/platform-tools/adb.exe'
if (Test-Path (Join-Path $warningQa 'emulator-results.json')) { throw 'Existing evidence preserved. Use a new output directory for another complete run.' }
& (Join-Path $PSScriptRoot 'review_project_20261008.ps1') -OutputDirectory $OutputDirectory
$warningResults = [System.Collections.Generic.List[object]]::new()
function Invoke-WarningReview([string]$Name, [string]$Class, [string[]]$Extra = @()) {
    $warningStart = Get-Date
    $warningLog = Join-Path $warningQa "$Name.log"
    & $warningAdb -s emulator-5554 shell am instrument -w -r -e class ("me.rerere.rikkahub.service." + $Class) @Extra org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> $warningLog
    $warningText = Get-Content -LiteralPath $warningLog -Raw
    $warningPass = $warningText -match 'OK \(\d+ tests?\)' -and $warningText -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'
    $warningResults.Add([pscustomobject]@{name=$Name;passed=$warningPass;started=$warningStart.ToString('o');seconds=[math]::Round(((Get-Date)-$warningStart).TotalSeconds,1);log="$Name.log"})
    $warningResults | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $warningQa 'additional-emulator-results.json') -Encoding utf8
    Write-Output "$Name : $warningPass"
}
Invoke-WarningReview 'gallery-final' 'WarningFixGalleryTest'
Invoke-WarningReview 'slider-final' 'WarningFixSliderTest'
Invoke-WarningReview 'workspace-boundary-final' 'WarningReviewBoundaryTest'
$warningFeedback = [guid]::NewGuid().ToString()
Invoke-WarningReview 'feedback-seed' 'FrontendFeedbackProcessTest' @('-e','feedbackFixture',$warningFeedback,'-e','feedbackPhase','seed')
& $warningAdb -s emulator-5554 shell am force-stop org.mathstudy.agent.debug
Invoke-WarningReview 'feedback-verify' 'FrontendFeedbackProcessTest' @('-e','feedbackFixture',$warningFeedback,'-e','feedbackPhase','verify')
# These tests isolate synthetic owners and restore the original selected configuration.
# Each test gets one invocation; failures are recorded without automatic remote retries.
foreach ($warningClass in @('StudyRealModelTest','ProjectReviewTeachingTest','LearningLoopRealModelTest','LearningMemoryRealModelTest','MaterialRealModelTest')) {
    Invoke-WarningReview $warningClass $warningClass @('-e','realModel','true')
}
Write-Output 'Complete run finished. All raw results, including failures and skipped placeholders, are retained.'
