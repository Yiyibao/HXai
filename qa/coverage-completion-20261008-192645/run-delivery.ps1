$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$adb = "$root/.local/android-sdk/platform-tools/adb.exe"
$output = "$PSScriptRoot/delivery-runs"
New-Item -ItemType Directory -Path $output | Out-Null
$runs = @()
$contexts = @()
for ($index = 1; $index -le 2; $index++) {
    $runId = [guid]::NewGuid().ToString()
    $started = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $name = "delivery-$index"
    & $adb -s emulator-5554 shell am instrument -w -r -e class me.rerere.rikkahub.service.ProjectReviewDeliveryTest -e reviewRunId $runId org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> "$output/$name.log"
    $ended = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $raw = Get-Content "$output/$name.log" -Raw
    $ok = $raw -match 'OK \(1 test\)' -and $raw -notmatch 'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -3'
    $runs += [pscustomobject]@{name=$name;runId=$runId;started=$started;ended=$ended;ok=$ok;exit=$LASTEXITCODE}
    $runs | ConvertTo-Json -Depth 6 | Set-Content "$output/results.json"
    $contexts += [pscustomobject]@{method='ProjectReviewDeliveryTest.everyFormatOpensSavesAndArrivesInOtherUid';runId=$runId;log="$name.log";logSha256=(Get-FileHash "$output/$name.log").Hash.ToLower();notBefore=$started-5000;notAfter=$ended+5000}
    Write-Output "$name $ok"
    if (-not $ok) { break }
}
[pscustomobject]@{appSha256=(Get-FileHash "$root/client/app/build/outputs/apk/debug/app-x86_64-debug.apk").Hash.ToLower();testSha256=(Get-FileHash "$root/client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk").Hash.ToLower();runs=$contexts} | ConvertTo-Json -Depth 8 | Set-Content "$output/run-context.json"
