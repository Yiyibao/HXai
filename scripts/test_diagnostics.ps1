param([string]$Serial = 'emulator-5554', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
if ($Serial -notmatch '^emulator-\d+$') { throw 'Only the development emulator may be modified.' }
$taskAdb = Join-Path $taskRoot '.local/android-sdk/platform-tools/adb.exe'
if (-not $SkipBuild) {
    $taskFirebase = Join-Path $taskRoot 'client/app/google-services.json'
    $taskAdded = -not (Test-Path -LiteralPath $taskFirebase)
    if ($taskAdded) { Copy-Item -LiteralPath (Join-Path $taskRoot 'qa/s0/google-services.example.json') -Destination $taskFirebase }
    try {
        & (Join-Path $taskRoot '.local/gradle-9.6.0/bin/gradle.bat') -p (Join-Path $taskRoot 'client') :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon --console=plain *> (Join-Path $taskRoot '.local/diagnostics-final-build.log')
        if ($LASTEXITCODE -ne 0) { throw 'Build failed; see .local/diagnostics-final-build.log.' }
    } finally { if ($taskAdded) { Remove-Item -LiteralPath $taskFirebase } }
}
$taskReceiver = & $taskAdb -s $Serial shell pm path org.mathstudy.qa.delivery
if (-not ($taskReceiver -match 'package:')) {
    if ($Serial -ne 'emulator-5554') { throw 'Install the QA receiver on the selected emulator first.' }
    & (Join-Path $taskRoot 'scripts/build_material_receiver.ps1')
}
& $taskAdb -s $Serial install -r (Join-Path $taskRoot 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'App install failed.' }
& $taskAdb -s $Serial install -r (Join-Path $taskRoot 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test APK install failed.' }
& $taskAdb -s $Serial shell am instrument -w -r -e class 'me.rerere.rikkahub.service.DiagnosticsTest,me.rerere.rikkahub.service.DiagnosticsPageTest' org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> (Join-Path $taskRoot '.local/diagnostics-android.log')
$taskOutput = Get-Content -LiteralPath (Join-Path $taskRoot '.local/diagnostics-android.log') -Raw
if ($taskOutput -notmatch 'OK \(\d+ tests?\)' -or $taskOutput -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') { throw 'Diagnostics tests failed; see .local/diagnostics-android.log.' }
Write-Output ($taskOutput | Select-String 'OK \(\d+ tests?\)' | ForEach-Object { $_.Matches.Value })
