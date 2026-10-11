$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$adb = "$root/.local/android-sdk/platform-tools/adb.exe"
$records = @()
foreach ($entry in @(@('material-page','MaterialPageTest'),@('delivery-material','MaterialDeliveryTest'),@('delivery-artifact','ProjectReviewDeliveryTest'))) {
 $started = (Get-Date).ToString('o')
 & $adb -s emulator-5554 shell am instrument -w -r -e class "me.rerere.rikkahub.service.$($entry[1])" org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> "$PSScriptRoot/$($entry[0]).log"
 $raw = Get-Content -Raw "$PSScriptRoot/$($entry[0]).log"
 $records += [pscustomobject]@{name=$entry[0];class=$entry[1];started=$started;ended=(Get-Date).ToString('o');ok=($raw -match 'OK \(\d+ tests?\)' -and $raw -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed');exit=$LASTEXITCODE}
 $records | ConvertTo-Json -Depth 5 | Set-Content "$PSScriptRoot/delivery-batch.json"
 Write-Output "$($entry[0]): $($records[-1].ok)"
}
