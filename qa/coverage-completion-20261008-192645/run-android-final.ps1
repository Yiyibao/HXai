$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$adb = "$root/.local/android-sdk/platform-tools/adb.exe"
$evidenceRoot = "$PSScriptRoot/final-android"
New-Item -ItemType Directory -Path $evidenceRoot | Out-Null
$records = @()
$contextRuns = @()
function RunCase($name, $class, $extra = @()) {
    $started = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $runId = [guid]::NewGuid().ToString()
    & $adb -s emulator-5554 shell am instrument -w -r -e class "me.rerere.rikkahub.service.$class" -e reviewRunId $runId @extra org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> "$evidenceRoot/$name.log"
    $exitCode = $LASTEXITCODE
    $ended = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $raw = Get-Content -Raw "$evidenceRoot/$name.log"
    $ok = $raw -match 'OK \(\d+ tests?\)' -and $raw -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -3'
    $script:records += [pscustomobject]@{name=$name;class=$class;runId=$runId;started=$started;ended=$ended;ok=$ok;exit=$exitCode}
    $script:records | ConvertTo-Json -Depth 6 | Set-Content "$evidenceRoot/android-results.json"
    foreach ($match in [regex]::Matches($raw, 'INSTRUMENTATION_STATUS: qa_evidence_manifest=(.*)')) {
        $manifest = $match.Groups[1].Value.Trim() | ConvertFrom-Json
        $script:contextRuns += [pscustomobject]@{method=$manifest.method;runId=$manifest.runId;log="$name.log";logSha256=(Get-FileHash "$evidenceRoot/$name.log").Hash.ToLower();notBefore=$started-5000;notAfter=$ended+5000}
    }
    Write-Output "$name $ok"
    return $ok
}
$app = "$root/client/app/build/outputs/apk/debug/app-x86_64-debug.apk"
$test = "$root/client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
& $adb -s emulator-5554 install -r $app *> "$evidenceRoot/install-app.log"
if ($LASTEXITCODE -ne 0) { throw 'App install failed' }
& $adb -s emulator-5554 install -r $test *> "$evidenceRoot/install-test.log"
if ($LASTEXITCODE -ne 0) { throw 'Test install failed' }
Get-FileHash $app,$test | Select-Object Path,Hash | ConvertTo-Json | Set-Content "$evidenceRoot/runtime-apk-hashes.json"
foreach ($entry in @(
    @('skills','SkillsInstallBoundaryTest'),
    @('word','WordFormulaAssertionTest'),
    @('diagnostics','DiagnosticsTest'),
    @('context-privacy','WarningReviewContextPrivacyTest'),
    @('skills-install','ChecklistSupplementTest#skillsInstallWorksWhileAuthoringAndEditingAreDisabled'),
    @('skills-enabled','ChecklistSupplementTest#installedSkillsCanBeUsedOnlyWhenEnabled')
)) { $null = RunCase $entry[0] $entry[1] }
$fixture = [guid]::NewGuid().ToString()
$fixture | Set-Content "$evidenceRoot/fixture-id.txt"
$seed = RunCase 'seed' 'ChecklistSupplementTest#controlledBoundariesSurviveIndependentProcess' @('-e','fixtureId',$fixture,'-e','phase','seed')
if ($seed[-1] -eq $true) {
    & $adb -s emulator-5554 shell am force-stop org.mathstudy.agent.debug
    $null = RunCase 'verify' 'ChecklistSupplementTest#controlledBoundariesSurviveIndependentProcess' @('-e','fixtureId',$fixture,'-e','phase','verify')
} else {
    $null = RunCase 'cleanup' 'ChecklistSupplementTest#controlledBoundariesSurviveIndependentProcess' @('-e','fixtureId',$fixture,'-e','phase','cleanup')
}
[pscustomobject]@{appSha256=(Get-FileHash $app).Hash.ToLower();testSha256=(Get-FileHash $test).Hash.ToLower();runs=$contextRuns} | ConvertTo-Json -Depth 8 | Set-Content "$evidenceRoot/run-context.json"

