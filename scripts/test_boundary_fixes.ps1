param([switch]$RealModel, [switch]$SkipInstall, [string]$FromCase = '', [string]$OutputDirectory = 'qa/boundary-fixes-20261008')
$ErrorActionPreference = 'Stop'
$boundaryRoot = Split-Path $PSScriptRoot -Parent
$boundaryQa = Join-Path $boundaryRoot $OutputDirectory
$boundaryAdb = Join-Path $boundaryRoot '.local/android-sdk/platform-tools/adb.exe'
New-Item -ItemType Directory -Force $boundaryQa | Out-Null
$boundaryResults = [System.Collections.Generic.List[object]]::new()
if ($FromCase -and (Test-Path (Join-Path $boundaryQa 'runtime-results.json'))) {
    foreach ($boundaryPrevious in (Get-Content -Raw (Join-Path $boundaryQa 'runtime-results.json') | ConvertFrom-Json)) {
        if (-not $boundaryPrevious.passed -and $boundaryPrevious.log -eq "$($boundaryPrevious.name).log") {
            $boundaryFailureStamp = ([datetimeoffset]::Parse($boundaryPrevious.started)).ToString('yyyyMMddHHmmss')
            $boundaryFailureLog = $boundaryPrevious.log.Replace('.log',"-failure-$boundaryFailureStamp.log")
            Copy-Item -LiteralPath (Join-Path $boundaryQa $boundaryPrevious.log) -Destination (Join-Path $boundaryQa $boundaryFailureLog)
            $boundaryPrevious.log = $boundaryFailureLog
        }
        $boundaryResults.Add($boundaryPrevious)
    }
}
$boundaryApks = @('client/app/build/outputs/apk/debug/app-x86_64-debug.apk', 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
$boundaryApks | ForEach-Object { Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $boundaryRoot $_) } | Select-Object Path,Hash | ConvertTo-Json | Set-Content (Join-Path $boundaryQa 'runtime-apk-hashes.json')
foreach ($boundaryApk in $(if ($SkipInstall) { @() } else { $boundaryApks })) {
    & $boundaryAdb -s emulator-5554 install -r (Join-Path $boundaryRoot $boundaryApk) >> (Join-Path $boundaryQa 'install.log')
    if ($LASTEXITCODE -ne 0) { throw 'Boundary APK install failed' }
}
function Invoke-BoundaryCase([string]$Name, [string]$Class, [string[]]$Extra = @()) {
    $boundaryStart = Get-Date
    $boundaryLog = Join-Path $boundaryQa "$Name.log"
    & $boundaryAdb -s emulator-5554 shell am instrument -w -r -e class "me.rerere.rikkahub.service.$Class" @Extra org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> $boundaryLog
    $boundaryText = Get-Content -Raw -LiteralPath $boundaryLog
    $boundaryPass = $boundaryText -match 'OK \(\d+ tests?\)' -and $boundaryText -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'
    $boundaryResults.Add([pscustomobject]@{name=$Name; passed=$boundaryPass; started=$boundaryStart.ToString('o'); seconds=[math]::Round(((Get-Date)-$boundaryStart).TotalSeconds,1); log="$Name.log"})
    $boundaryResults | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $boundaryQa 'runtime-results.json')
    Write-Output "$Name : $boundaryPass"
    if (-not $boundaryPass) { throw "Boundary case failed: $Name; original log retained." }
}
$boundaryClasses = @('ConversationBoundaryFixTest','WordFormulaAssertionTest','StudyChatCompletionTest','ChatNetworkFaultTest','ProjectReviewAuditTest','MaterialFilesTest','MaterialFidelityTest','LearningMemoryPersistenceTest','BackupReliabilityTest','MaterialDeliveryTest','ProjectReviewDeliveryTest','MaterialPageTest')
if ($FromCase) {
    $boundaryIndex = [array]::IndexOf($boundaryClasses, $FromCase)
    if ($boundaryIndex -lt 0) { throw 'Unknown boundary case' }
    $boundaryClasses = $boundaryClasses[$boundaryIndex..($boundaryClasses.Length-1)]
}
foreach ($boundaryClass in $boundaryClasses) {
    Invoke-BoundaryCase $boundaryClass $boundaryClass
}
$boundaryFixture = [guid]::NewGuid().ToString()
Invoke-BoundaryCase 'runtime-seed' 'RuntimeProcessRecoveryTest' @('-e','fixtureId',$boundaryFixture,'-e','phase','seed')
& $boundaryAdb -s emulator-5554 shell am force-stop org.mathstudy.agent.debug
Invoke-BoundaryCase 'runtime-verify' 'RuntimeProcessRecoveryTest' @('-e','fixtureId',$boundaryFixture,'-e','phase','verify')
if ($RealModel) {
    Invoke-BoundaryCase 'real-teaching-approval' 'StudyRealModelTest' @('-e','realModel','true')
    Invoke-BoundaryCase 'real-material' 'MaterialRealModelTest' @('-e','realModel','true')
}
