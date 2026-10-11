param([string]$Serial = 'emulator-5554', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskAdb = Join-Path $taskRoot '.local/android-sdk/platform-tools/adb.exe'
$taskPackage = 'org.mathstudy.agent.debug'
# The shared runner guards emulator-only installation and checks the JUnit result.
& (Join-Path $PSScriptRoot 'test_s1_network_faults.ps1') -Serial $Serial -SkipBuild:$SkipBuild
$taskFixture = [guid]::NewGuid().ToString()
$taskFixture | Set-Content -LiteralPath (Join-Path $taskRoot '.local/s1-runtime-fixture-id.txt')
$taskPids = @{}
foreach ($taskPhase in @('seed', 'verify')) {
    if ($taskPhase -eq 'verify') {
        & $taskAdb -s $Serial shell am force-stop $taskPackage
        if ($LASTEXITCODE -ne 0) { throw 'Fixture force-stop failed.' }
    }
    $taskLog = Join-Path $taskRoot ".local/s1-runtime-process-$taskPhase.log"
    & $taskAdb -s $Serial shell am instrument -w -r -e class me.rerere.rikkahub.service.RuntimeProcessRecoveryTest -e fixtureId $taskFixture -e phase $taskPhase "$taskPackage.test/androidx.test.runner.AndroidJUnitRunner" *> $taskLog
    $taskRaw = Get-Content -LiteralPath $taskLog -Raw
    if ($taskRaw -notmatch 'OK \(1 test\)' -or $taskRaw -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        Get-Content -LiteralPath $taskLog -Tail 45
        throw "Runtime process $taskPhase failed."
    }
    $taskPidMatch = [regex]::Match($taskRaw, 'qa_process_pid=(\d+)')
    if (-not $taskPidMatch.Success) { throw 'Instrumentation did not report its application PID.' }
    $taskPids[$taskPhase] = $taskPidMatch.Groups[1].Value
}
if (-not $taskPids.seed -or -not $taskPids.verify -or $taskPids.seed -eq $taskPids.verify) {
    throw 'Could not verify separate application processes.'
}
$taskNetwork = Get-Content -LiteralPath (Join-Path $taskRoot 'qa/s1/network-faults-20261006.json') -Raw | ConvertFrom-Json
$taskEvidence = [ordered]@{
    date = Get-Date -Format 'yyyy-MM-dd'
    status = 'PASS'
    environment = 'AOSP API 34 emulator; synthetic inputs and tools; not Mate 50'
    serial = $Serial
    apk_sha256 = $taskNetwork.apk_sha256
    android_fault_tests = $taskNetwork.android_tests
    process_recovery_phases = 2
    process_ids = $taskPids
    jvm = $taskNetwork.jvm
    cases = @('real airplane-mode loss and manual queue resume', 'Wi-Fi to mobile default route switch', 'atomic durable queue submission', 'tool start/result commit before next execution', 'unknown effect never replayed; explicit skip and continuation', 'Room 27 to 28 migration', 'force-stop between checkpoint seed and recovery; stable message and queue IDs; paused queue; unknown tool state')
    limitations = @('Not target Mate 50', 'Local SSE fixture observes real route changes; no remote-provider dual-route test', 'Process fixture force-stops after durable checkpoints, not while a real external tool produces effects')
}
$taskEvidence | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskRoot 'qa/s1/runtime-continuation-20261006.json') -Encoding utf8
$taskEvidence | ConvertTo-Json -Depth 6
