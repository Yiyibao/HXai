$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$adb = "$root/.local/android-sdk/platform-tools/adb.exe"
Copy-Item "$root/client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$PSScriptRoot/probe-test-fixture-fixed.apk"
& $adb -s emulator-5554 install -r "$PSScriptRoot/probe-test-fixture-fixed.apk" *> "$PSScriptRoot/probe-install-fixture-fixed.log"
if ($LASTEXITCODE -ne 0) { throw 'Install failed' }
$runs = @(); $contexts = @()
foreach ($method in @('deletedConversationMustRejectStaleSaveAfterSessionEviction','completeServiceFactoryContinuationUsesSyntheticProviderAndFirstDecision')) {
    $uuid = [guid]::NewGuid().ToString(); $start = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $log = "$method-fixture-fixed.log"
    & $adb -s emulator-5554 shell am instrument -w -r -e class "me.rerere.rikkahub.service.IndependentPostfixProbeTest#$method" -e reviewRunId $uuid org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> "$PSScriptRoot/$log"
    $exit = $LASTEXITCODE; $end = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); $raw = Get-Content "$PSScriptRoot/$log" -Raw
    $runs += [pscustomobject]@{method=$method;runId=$uuid;started=$start;ended=$end;exit=$exit;ok=($raw -match 'OK \(1 test\)' -and $raw -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed')}
    foreach ($match in [regex]::Matches($raw,'INSTRUMENTATION_STATUS: qa_evidence_manifest=(.*)')) {
        $m = $match.Groups[1].Value.Trim() | ConvertFrom-Json
        $contexts += [pscustomobject]@{method=$m.method;runId=$m.runId;log=$log;logSha256=(Get-FileHash "$PSScriptRoot/$log").Hash.ToLower();notBefore=$start-5000;notAfter=$end+5000}
    }
    $runs | ConvertTo-Json | Set-Content "$PSScriptRoot/probe-android-results-fixture-fixed.json"
}
[pscustomobject]@{appSha256=(Get-FileHash "$root/client/app/build/outputs/apk/debug/app-x86_64-debug.apk").Hash.ToLower();testSha256=(Get-FileHash "$PSScriptRoot/probe-test-fixture-fixed.apk").Hash.ToLower();runs=$contexts} | ConvertTo-Json -Depth 8 | Set-Content "$PSScriptRoot/probe-run-context-fixture-fixed.json"
