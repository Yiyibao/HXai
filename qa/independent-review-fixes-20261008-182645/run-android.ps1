$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$adb = "$root/.local/android-sdk/platform-tools/adb.exe"
$records = @()
$contextRuns = @()
function RunCase($name, $class, $extra = @(), $evidenceMethod = '') {
 $started = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
 $runId = [guid]::NewGuid().ToString()
 & $adb -s emulator-5554 shell am instrument -w -r -e class "me.rerere.rikkahub.service.$class" -e reviewRunId $runId @extra org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> "$PSScriptRoot/$name.log"
 $ended = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
 $raw = Get-Content -Raw "$PSScriptRoot/$name.log"
 $ok = $raw -match 'OK \(\d+ tests?\)' -and $raw -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -3'
 $script:records += [pscustomobject]@{name=$name;class=$class;runId=$runId;started=$started;ended=$ended;ok=$ok;exit=$LASTEXITCODE}
 $script:records | ConvertTo-Json -Depth 6 | Set-Content "$PSScriptRoot/android-results.json"
 if ($evidenceMethod -ne '') {
  $script:contextRuns += [pscustomobject]@{method=$evidenceMethod;runId=$runId;log="$name.log";logSha256=(Get-FileHash "$PSScriptRoot/$name.log").Hash.ToLower();notBefore=$started-5000;notAfter=$ended+5000}
 }
 Write-Output "$name $ok"
 return $ok
}
$appApk = "$root/client/app/build/outputs/apk/debug/app-x86_64-debug.apk"
$testApk = "$root/client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
& $adb -s emulator-5554 install -r $appApk *> "$PSScriptRoot/install-app.log"
if ($LASTEXITCODE -ne 0) { throw 'App install failed' }
& $adb -s emulator-5554 install -r $testApk *> "$PSScriptRoot/install-test.log"
if ($LASTEXITCODE -ne 0) { throw 'Test install failed' }
Get-FileHash $appApk,$testApk | Select-Object Path,Hash | ConvertTo-Json | Set-Content "$PSScriptRoot/runtime-apk-hashes.json"
foreach ($entry in @(
 @('diagnostics','DiagnosticsTest'),
 @('skills-bounds','SkillsInstallBoundaryTest'),
 @('skills-install','ChecklistSupplementTest#skillsInstallWorksWhileAuthoringAndEditingAreDisabled'),
 @('skills-use','ChecklistSupplementTest#installedSkillsCanBeUsedOnlyWhenEnabled')
)) { $null = RunCase $entry[0] $entry[1] }
$null = RunCase 'evidence-fresh' 'QaEvidenceRunTest#freshSyntheticEvidence' @() 'QaEvidenceRunTest.freshSyntheticEvidence'
$null = RunCase 'evidence-failed-status' 'QaEvidenceRunTest#failureEvidenceIsRetainedInSeparateMethodDirectory' @() 'QaEvidenceRunTest.failureEvidenceIsRetainedInSeparateMethodDirectory'
[pscustomobject]@{appSha256=(Get-FileHash $appApk).Hash.ToLower();testSha256=(Get-FileHash $testApk).Hash.ToLower();runs=$contextRuns} | ConvertTo-Json -Depth 8 | Set-Content "$PSScriptRoot/run-context.json"
$fixture = [guid]::NewGuid().ToString()
$fixture | Set-Content "$PSScriptRoot/fixture-id.txt"
$seed = RunCase 'seed' 'ChecklistSupplementTest#controlledBoundariesSurviveIndependentProcess' @('-e','fixtureId',$fixture,'-e','phase','seed')
if ($seed[-1] -eq $true) {
 & $adb -s emulator-5554 shell am force-stop org.mathstudy.agent.debug
 $null = RunCase 'verify' 'ChecklistSupplementTest#controlledBoundariesSurviveIndependentProcess' @('-e','fixtureId',$fixture,'-e','phase','verify')
}
