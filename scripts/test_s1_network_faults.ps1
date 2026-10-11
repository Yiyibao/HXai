param([string]$Serial = 'emulator-5554', [switch]$SkipBuild, [switch]$KeepEvidence)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskAdb = Join-Path $taskRoot '.local/android-sdk/platform-tools/adb.exe'
$taskClient = Join-Path $taskRoot 'client'
$taskLog = Join-Path $taskRoot '.local/s1-network-fault-tests.log'
$taskPackage = 'org.mathstudy.agent.debug'

if ($Serial -notmatch '^emulator-\d+$') { throw 'This synthetic QA runner only installs on an explicitly selected emulator.' }
& $taskAdb -s $Serial get-state | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Selected emulator is unavailable.' }

if (-not $SkipBuild) {
    $taskFirebase = Join-Path $taskClient 'app/google-services.json'
    $taskAddedFirebase = -not (Test-Path -LiteralPath $taskFirebase)
    if ($taskAddedFirebase) { Copy-Item -LiteralPath (Join-Path $taskRoot 'qa/s0/google-services.example.json') -Destination $taskFirebase }
    try {
        & (Join-Path $taskRoot '.local/gradle-9.6.0/bin/gradle.bat') -p $taskClient :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :ai:testDebugUnitTest --console=plain *> (Join-Path $taskRoot '.local/s1-network-fault-build.log')
        if ($LASTEXITCODE -ne 0) { throw 'Build failed; see .local/s1-network-fault-build.log.' }
    } finally {
        if ($taskAddedFirebase) { Remove-Item -LiteralPath $taskFirebase }
    }
}

$taskApk = Join-Path $taskClient 'app/build/outputs/apk/debug/app-x86_64-debug.apk'
& $taskAdb -s $Serial install -r $taskApk
if ($LASTEXITCODE -ne 0) { throw 'Application install failed.' }
& $taskAdb -s $Serial install -r (Join-Path $taskClient 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Instrumentation install failed.' }
$taskArgs = @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'me.rerere.rikkahub.service.ChatNetworkFaultTest')
if ($KeepEvidence) { $taskArgs += @('-e', 'keepEvidence', 'true') }
$taskArgs += "$taskPackage.test/androidx.test.runner.AndroidJUnitRunner"
& $taskAdb @taskArgs *> $taskLog
$taskRaw = Get-Content -LiteralPath $taskLog -Raw
$taskMatch = [regex]::Match($taskRaw, 'OK \((\d+) tests?\)')
if (-not $taskMatch.Success -or $taskRaw -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
    Get-Content -LiteralPath $taskLog -Tail 50
    throw 'Android fault tests failed; see .local/s1-network-fault-tests.log.'
}
$taskJvm = @{}
foreach ($taskModule in @('app', 'ai')) {
    $taskCount = 0; $taskFailures = 0; $taskErrors = 0
    Get-ChildItem -LiteralPath (Join-Path $taskClient "$taskModule/build/test-results/testDebugUnitTest") -Filter 'TEST-*.xml' | ForEach-Object {
        [xml]$taskReport = Get-Content -LiteralPath $_.FullName
        $taskCount += [int]$taskReport.testsuite.tests
        $taskFailures += [int]$taskReport.testsuite.failures
        $taskErrors += [int]$taskReport.testsuite.errors
    }
    $taskJvm[$taskModule] = @{ tests = $taskCount; failures = $taskFailures; errors = $taskErrors }
}
$taskEvidence = [ordered]@{
    date = (Get-Date -Format 'yyyy-MM-dd')
    environment = 'AOSP emulator; localhost HTTP/SSE faults plus real default-network transitions; not Mate 50'
    serial = $Serial
    sdk = (& $taskAdb -s $Serial shell getprop ro.build.version.sdk).Trim()
    application_id = $taskPackage
    apk_sha256 = (Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant()
    android_tests = [int]$taskMatch.Groups[1].Value
    status = 'PASS'
    jvm = $taskJvm
    keep_visual_fixture = [bool]$KeepEvidence
    cases = @('Responses silent SSE EOF', 'TCP reset after partial output', 'HTTP 401/429 with JSON and empty bodies', 'manual regeneration without duplicate user input', 'regeneration failure before first chunk preserves interrupted branch', 'cancel stalled stream', 'real socket read timeout classification', 'Chat Completions EOF/completion/empty HTTP error', 'Room restart recovery with preserved message IDs', 'device airplane mode interruption and explicit queue resume', 'Wi-Fi to cellular default-network switch without replay', 'durable queue and atomic submission', 'per-tool durable checkpoints and unknown-effect skip', 'Room 27 to 28 migration', 'tool network error leaves unknown effects and blocks next tool', 'resumed Auto tool rechecks approval')
    limitations = @('No target Mate 50 test', 'Timeout uses a short fixture client rather than production ten-minute read timeout', 'Localhost SSE fixture observes real device route changes; it does not validate a remote provider over both routes', 'Separate runtime continuation runner verifies force-stop recovery; this suite uses fresh sessions')
}
$taskJson = $taskEvidence | ConvertTo-Json -Depth 6
$taskJson | Set-Content -LiteralPath (Join-Path $taskRoot 'qa/s1/network-faults-20261006.json') -Encoding utf8
Write-Output $taskJson
