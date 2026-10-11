$ErrorActionPreference = 'Stop'
$rrRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$rrAdb = Join-Path $rrRoot '.local/android-sdk/platform-tools/adb.exe'
$rrResults = [System.Collections.Generic.List[object]]::new()
function Invoke-RR([string]$Name, [string]$Class, [string[]]$Extra = @()) {
    $rrStarted = Get-Date
    $rrLog = Join-Path $PSScriptRoot "$Name.log"
    & $rrAdb -s emulator-5554 shell am instrument -w -r -e class "me.rerere.rikkahub.service.$Class" @Extra org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> $rrLog
    $rrText = Get-Content -Raw -LiteralPath $rrLog
    $rrPassed = $rrText -match 'OK \(\d+ tests?\)' -and $rrText -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'
    $rrResults.Add([pscustomobject]@{name=$Name;passed=$rrPassed;started=$rrStarted.ToString('o');seconds=[math]::Round(((Get-Date)-$rrStarted).TotalSeconds,1);log="$Name.log"})
    $rrResults | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $PSScriptRoot 'final-results.json')
    Write-Output "$Name : $rrPassed"
    return $rrPassed
}
& $rrAdb -s emulator-5554 install -r (Join-Path $rrRoot 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk') *> (Join-Path $PSScriptRoot 'install-app-final.log')
& $rrAdb -s emulator-5554 install -r (Join-Path $rrRoot 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk') *> (Join-Path $PSScriptRoot 'install-test-final.log')
Get-FileHash (Join-Path $rrRoot 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk'),(Join-Path $rrRoot 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk') | Select-Object Path,Hash | ConvertTo-Json | Set-Content (Join-Path $PSScriptRoot 'final-apk-hashes.json')
$rrFixture = [guid]::NewGuid().ToString()
$rrFixture | Set-Content (Join-Path $PSScriptRoot 'fixture-final.txt')
$rrSeed = Invoke-RR 'seed-final' 'ChecklistSupplementTest#controlledBoundariesSurviveIndependentProcess' @('-e','fixtureId',$rrFixture,'-e','phase','seed')
if ($rrSeed[-1] -eq $true) {
    & $rrAdb -s emulator-5554 shell am force-stop org.mathstudy.agent.debug
    $null = Invoke-RR 'verify-final' 'ChecklistSupplementTest#controlledBoundariesSurviveIndependentProcess' @('-e','fixtureId',$rrFixture,'-e','phase','verify')
}
foreach ($rrEntry in @(
    @('skills-install','ChecklistSupplementTest#skillsInstallWorksWhileAuthoringAndEditingAreDisabled'),
    @('skills-use','ChecklistSupplementTest#installedSkillsCanBeUsedOnlyWhenEnabled'),
    @('word-negative','WordFormulaAssertionTest'),
    @('diagnostics','DiagnosticsTest'),
    @('boundary','ConversationBoundaryFixTest'),
    @('network','ChatNetworkFaultTest'),
    @('material-fidelity','MaterialFidelityTest'),
    @('material-page','MaterialPageTest'),
    @('delivery','MaterialDeliveryTest'),
    @('project-delivery','ProjectReviewDeliveryTest')
)) { $null = Invoke-RR $rrEntry[0] $rrEntry[1] }
# Once on this version; never rewrite/retry a remote rejection or uncertain tool.
$null = Invoke-RR 'teaching-evidence' 'StudyRealModelTest' @('-e','realModel','true')
