param([switch]$InstallBaseline)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$adbPath = Join-Path $taskRoot '.local/android-sdk/platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adbPath)) { throw 'Run python scripts/setup_android_tools.py first.' }
$devices = & $adbPath devices
$connected = @($devices | Where-Object { $_ -match '^\S+\s+device$' })
if ($connected.Count -ne 1) { throw 'Connect exactly one authorized USB debugging device, then retry.' }
$serial = ($connected[0] -split '\s+')[0]
function Read-Prop([string]$name) {
    return ((& $adbPath -s $serial shell getprop $name) -join '').Trim()
}
$result = [ordered]@{
    checked_at = (Get-Date).ToUniversalTime().ToString('o')
    manufacturer = Read-Prop 'ro.product.manufacturer'
    model = Read-Prop 'ro.product.model'
    android_release = Read-Prop 'ro.build.version.release'
    sdk = Read-Prop 'ro.build.version.sdk'
    display_build = Read-Prop 'ro.build.display.id'
    harmony_version = Read-Prop 'hw_sc.build.platform.version'
    abi = Read-Prop 'ro.product.cpu.abilist'
    install = 'NOT_RUN'
    launch = 'NOT_RUN'
    reopen = 'NOT_RUN'
    manual_formula_file_network = 'NOT_RUN'
}
if ($result.model -notmatch 'CET-AL00|Mate 50') { throw 'Connected model does not match the planned Mate 50; verify device before installation.' }
if ($InstallBaseline) {
    $apkPath = Join-Path $taskRoot '.local/rikkahub-2.5.6-arm64-v8a.apk'
    $digest = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($digest -ne 'f0a2cbe2224a22f439921f66c65682f43e8a3009347754291ea6a0a83de57af6') { throw 'APK checksum mismatch.' }
    $installed = & $adbPath -s $serial install -r $apkPath 2>&1
    if ($LASTEXITCODE -ne 0 -or ($installed -join '') -notmatch 'Success') { throw 'APK installation failed; examine local device output.' }
    $result.install = 'PASS'
    $result.launch = 'MANUAL_REQUIRED'
    $result.reopen = 'MANUAL_REQUIRED'
}
$privatePath = Join-Path $taskRoot 'qa/s0/private'
New-Item -ItemType Directory -Force $privatePath | Out-Null
$result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $privatePath 'device.json') -Encoding utf8
$result | ConvertTo-Json
