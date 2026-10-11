param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskAdb = Join-Path $taskRoot '.local/android-sdk/platform-tools/adb.exe'
$taskPackage = 'org.mathstudy.agent.debug'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Select an emulator for credential QA.' }
& $taskAdb -s $Serial get-state | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Emulator is unavailable.' }
$taskApk = Join-Path $taskRoot 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk'
& $taskAdb -s $Serial install -r $taskApk
if ($LASTEXITCODE -ne 0) { throw 'Application install failed.' }
& $taskAdb -s $Serial install -r (Join-Path $taskRoot 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Instrumentation install failed.' }
$taskLogs = @()
foreach ($taskPass in @('initial', 'cold-restart')) {
    & $taskAdb -s $Serial shell am force-stop $taskPackage
    if ($LASTEXITCODE -ne 0) { throw 'Force-stop failed.' }
    $taskLog = Join-Path $taskRoot ".local/s1-credentials-$taskPass-20261007.log"
    & $taskAdb -s $Serial shell am instrument -w -r -e class me.rerere.rikkahub.service.CredentialVaultTest "$taskPackage.test/androidx.test.runner.AndroidJUnitRunner" *> $taskLog
    $taskRaw = Get-Content -LiteralPath $taskLog -Raw
    if ($taskRaw -notmatch 'OK \(10 tests\)' -or $taskRaw -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Credential tests failed; inspect $taskLog"
    }
    $taskLogs += $taskLog
}
$taskEvidence = [ordered]@{
    date = '2026-10-07'
    environment = 'AOSP emulator; synthetic Keystore tests and application settings/backup checks; not Mate 50'
    serial = $Serial
    apk_sha256 = (Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant()
    android_tests_per_pass = 10
    passes = @('initial', 'cold-restart')
    status = 'PASS'
    logs = $taskLogs
    limitations = @('Synthetic credential fault fixtures; no OEM permanent invalidation event', 'No cross-device restore', 'Not Mate 50')
}
$taskJson = $taskEvidence | ConvertTo-Json -Depth 4
$taskJson | Set-Content -LiteralPath (Join-Path $taskRoot 'qa/s1/credentials-20261007.json') -Encoding utf8
Write-Output $taskJson
