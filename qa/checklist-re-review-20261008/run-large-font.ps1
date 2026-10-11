$ErrorActionPreference = 'Stop'
$rrRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$rrAdb = Join-Path $rrRoot '.local/android-sdk/platform-tools/adb.exe'
$rrOriginalScale = (& $rrAdb -s emulator-5554 shell settings get system font_scale).Trim()
$rrStart = Get-Date
Set-Content (Join-Path $PSScriptRoot 'original-font-scale.txt') $rrOriginalScale
try {
    & $rrAdb -s emulator-5554 shell settings put system font_scale 1.3
    & $rrAdb -s emulator-5554 shell am force-stop org.mathstudy.agent.debug
    & $rrAdb -s emulator-5554 shell am instrument -w -r -e class 'me.rerere.rikkahub.service.ProjectReviewAuditTest#fourEntrancesAndCurrentTeachingSelectorWork' org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> (Join-Path $PSScriptRoot 'large-font.log')
    $rrText = Get-Content -Raw (Join-Path $PSScriptRoot 'large-font.log')
    $rrPass = $rrText -match 'OK \(1 test\)' -and $rrText -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'
    [ordered]@{name='large-font';font_scale='1.3';passed=$rrPass;log='large-font.log';restored_scale=$rrOriginalScale;started=$rrStart.ToString('o')} | ConvertTo-Json | Set-Content (Join-Path $PSScriptRoot 'large-font-result.json')
    Write-Output "large-font : $rrPass"
} finally {
    if ($rrOriginalScale -eq 'null') { & $rrAdb -s emulator-5554 shell settings delete system font_scale }
    else { & $rrAdb -s emulator-5554 shell settings put system font_scale $rrOriginalScale }
}
