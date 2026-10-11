param()
$ErrorActionPreference='Stop'
$reviewRoot=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$reviewAdb=Join-Path $reviewRoot '.local/android-sdk/platform-tools/adb.exe'
$reviewCases=@{
 'teaching-and-approval'='me.rerere.rikkahub.service.StudyRealModelTest#coldStartEncryptedConfigurationAndFourTeachingModes'
 'memory-real'='me.rerere.rikkahub.service.LearningMemoryRealModelTest'
 'learning-loop-real'='me.rerere.rikkahub.service.LearningLoopRealModelTest'
 'material-real'='me.rerere.rikkahub.service.MaterialRealModelTest'
}
foreach($reviewName in @('teaching-and-approval','memory-real','learning-loop-real','material-real')) {
 & $reviewAdb -s emulator-5554 shell am instrument -w -r -e class $reviewCases[$reviewName] -e realModel true org.mathstudy.agent.debug.test/androidx.test.runner.AndroidJUnitRunner *> (Join-Path $PSScriptRoot "$reviewName.log")
 $reviewText=Get-Content -LiteralPath (Join-Path $PSScriptRoot "$reviewName.log") -Raw
 Write-Output "$reviewName : $($reviewText -match 'OK \(\d+ tests?\)' -and $reviewText -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed')"
}
