param(
    [string]$WslDistribution,
    [string]$LinuxJreArchive,
    [string]$OutputDirectory = '.local/workspace-verification',
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path $PSScriptRoot -Parent
$workspaceOutput = [IO.Path]::GetFullPath((Join-Path $workspaceRoot $OutputDirectory))
New-Item -ItemType Directory -Force $workspaceOutput | Out-Null
$workspaceGradle = Join-Path $workspaceRoot '.local/gradle-9.6.0/bin/gradle.bat'
if (-not (Test-Path $workspaceGradle)) { $workspaceGradle = Join-Path $workspaceRoot 'client/gradlew.bat' }
$workspaceArgs = @('-p', (Join-Path $workspaceRoot 'client'), '--console=plain', '--no-configuration-cache')
if ($Offline) { $workspaceArgs += '--offline' }
# Run the four original shell assertions on Windows, including embedded quotes and stdin/EOF.
& $workspaceGradle @workspaceArgs :workspace:testDebugUnitTest --tests 'me.rerere.workspace.ExampleUnitTest.command*' --rerun-tasks *> (Join-Path $workspaceOutput 'windows-shell.log')
if ($LASTEXITCODE -ne 0) { throw 'Native Windows shell tests failed; see windows-shell.log.' }
$workspaceClasspath = Join-Path $workspaceOutput 'classpath.json'
& $workspaceGradle @workspaceArgs -I (Join-Path $PSScriptRoot 'workspace-test-classpath.init.gradle') :workspace:exportHostTestClasspath "-PworkspaceClasspathOutput=$workspaceClasspath" *> (Join-Path $workspaceOutput 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Workspace test compilation failed; see compile.log.' }
$workspaceWslArgs = @()
if ($WslDistribution) { $workspaceWslArgs = @('-d', $WslDistribution) }
function Convert-WorkspaceLinuxPath([string]$Path) {
    $result = & wsl @workspaceWslArgs -- wslpath -u $Path.Replace('\', '/')
    if ($LASTEXITCODE -ne 0) { throw "WSL could not map path: $Path" }
    return ($result | Select-Object -Last 1).Trim()
}
function Quote-WorkspaceShell([string]$Value) {
    return "'" + $Value.Replace("'", "'`"'`"'") + "'"
}
$workspaceData = Get-Content -LiteralPath $workspaceClasspath -Raw | ConvertFrom-Json
$workspaceMounts = @{}
$workspaceLinuxClasspath = foreach ($entry in $workspaceData.classpath) {
    if (Test-Path -LiteralPath $entry) {
        $drive = [IO.Path]::GetPathRoot($entry)
        if (-not $workspaceMounts.ContainsKey($drive)) { $workspaceMounts[$drive] = Convert-WorkspaceLinuxPath $drive }
        $workspaceMounts[$drive].TrimEnd('/') + '/' + $entry.Substring($drive.Length).Replace('\', '/')
    }
}
$workspaceClasses = foreach ($directory in $workspaceData.classes) {
    if (Test-Path -LiteralPath $directory) {
        Get-ChildItem -LiteralPath $directory -Recurse -Filter '*Test.class' | ForEach-Object {
            $_.FullName.Substring($directory.Length + 1).Replace('\', '.').Replace('/', '.').Replace('.class', '')
        }
    }
}
if (-not $workspaceClasses) { throw 'No compiled test classes found.' }
$workspaceJava = 'java'
$workspaceSetup = ''
if ($LinuxJreArchive) {
    $archive = (Resolve-Path -LiteralPath $LinuxJreArchive).Path
    $workspaceSetup = 'mkdir -p "$task_runtime/jre"' + "`n" + 'tar -xzf ' + (Quote-WorkspaceShell (Convert-WorkspaceLinuxPath $archive)) + ' -C "$task_runtime/jre" --strip-components=1'
    $workspaceJava = '"$task_runtime/jre/bin/java"'
}
# Linux temp storage is essential: rootfs symlinks cannot be emulated by file copies.
# /dev/shm also supports minimal/read-only WSL distributions without changing their installation.
$workspaceScript = @'
#!/bin/sh
set -eu
task_runtime=$(mktemp -d /dev/shm/mathstudy-workspace.XXXXXX)
trap 'case "$task_runtime" in /dev/shm/mathstudy-workspace.*) rm -rf "$task_runtime";; esac' EXIT
mkdir -p "$task_runtime/tmp"
__SETUP__
__JAVA__ -XX:-UsePerfData -Djava.io.tmpdir="$task_runtime/tmp" -cp __CLASSPATH__ org.junit.runner.JUnitCore __CLASSES__
'@
$workspaceScript = $workspaceScript.Replace('__SETUP__', $workspaceSetup).Replace('__JAVA__', $workspaceJava).Replace('__CLASSPATH__', (Quote-WorkspaceShell ($workspaceLinuxClasspath -join ':'))).Replace('__CLASSES__', (($workspaceClasses | ForEach-Object { Quote-WorkspaceShell $_ }) -join ' '))
$workspaceScriptPath = Join-Path $workspaceOutput 'run-posix.sh'
[IO.File]::WriteAllText($workspaceScriptPath, $workspaceScript.Replace("`r`n", "`n"), [Text.UTF8Encoding]::new($false))
$workspaceLinuxScript = Convert-WorkspaceLinuxPath $workspaceScriptPath
& wsl @workspaceWslArgs -- sh $workspaceLinuxScript *> (Join-Path $workspaceOutput 'posix-all.log')
$workspaceExit = $LASTEXITCODE
$workspaceResult = (Get-Content -LiteralPath (Join-Path $workspaceOutput 'posix-all.log') -Raw).Replace([string][char]0, '')
if ($workspaceExit -ne 0 -or $workspaceResult -notmatch 'OK \(\d+ tests\)') { throw 'Full POSIX suite failed; see posix-all.log.' }
Write-Output 'PASS: four original shell tests on Windows; full unchanged workspace suite on Linux.'
Write-Output $workspaceResult
