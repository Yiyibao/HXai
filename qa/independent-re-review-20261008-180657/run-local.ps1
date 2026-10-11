$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$adb = "$root/.local/android-sdk/platform-tools/adb.exe"
$fixture = [guid]::NewGuid().ToString()
$fixture | Set-Content "$PSScriptRoot/fixture-id.txt"
$records = @()
function RunCase($name, $class, $extra = @()) {
  $started = (Get-Date).ToString('o')
  & $adb -s emulator-5554 shell am instrument -w -r -e class "me.rerere.rikkahub.service.$class" @extra org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> "$PSScriptRoot/$name.log"
  $raw = Get-Content -Raw "$PSScriptRoot/$name.log"
  $ok = $raw -match 'OK \(\d+ tests?\)' -and $raw -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'
  $script:records += [pscustomobject]@{name=$name;started=$started;ended=(Get-Date).ToString('o');ok=$ok;exit=$LASTEXITCODE;class=$class}
  $script:records | ConvertTo-Json -Depth 6 | Set-Content "$PSScriptRoot/android-batch.json"
  Write-Output "$name $ok"
  return $ok
}
$seed = RunCase 'seed' 'ChecklistSupplementTest#controlledBoundariesSurviveIndependentProcess' @('-e','fixtureId',$fixture,'-e','phase','seed')
if ($seed[-1] -eq $true) {
  & $adb -s emulator-5554 shell am force-stop org.mathstudy.agent.debug
  $verify = RunCase 'verify' 'ChecklistSupplementTest#controlledBoundariesSurviveIndependentProcess' @('-e','fixtureId',$fixture,'-e','phase','verify')
}
foreach ($entry in @(
 @('skills-install','ChecklistSupplementTest#skillsInstallWorksWhileAuthoringAndEditingAreDisabled'),
 @('skills-use','ChecklistSupplementTest#installedSkillsCanBeUsedOnlyWhenEnabled'),
 @('word-negative','WordFormulaAssertionTest')
)) { $null = RunCase $entry[0] $entry[1] }
