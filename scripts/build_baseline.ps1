param([switch]$UsePlaceholderFirebase, [string]$ProxyHost, [int]$ProxyPort = 0)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$clientRoot = Join-Path $taskRoot 'client'
$sdkRoot = Join-Path $taskRoot '.local/android-sdk'
if (-not (Test-Path -LiteralPath (Join-Path $sdkRoot 'platforms/android-37.2/android.jar'))) { throw 'Install SDK platform 37.2 first.' }
$propertiesPath = Join-Path $clientRoot 'local.properties'
if (-not (Test-Path -LiteralPath $propertiesPath)) {
    Set-Content -LiteralPath $propertiesPath -Encoding ascii -Value ('sdk.dir=' + $sdkRoot.Replace('\','/'))
}
$firebasePath = Join-Path $clientRoot 'app/google-services.json'
if ($UsePlaceholderFirebase) {
    if (Test-Path -LiteralPath $firebasePath) { throw 'Existing Google Services configuration is preserved; omit placeholder flag or move it yourself.' }
    Copy-Item -LiteralPath (Join-Path $taskRoot 'qa/s0/google-services.example.json') -Destination $firebasePath
}
$s0OriginalJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    if ($ProxyHost) {
        if ($ProxyHost -notmatch '^[a-zA-Z0-9.\-]+$' -or $ProxyPort -lt 1 -or $ProxyPort -gt 65535) { throw 'Invalid proxy address.' }
        $env:JAVA_TOOL_OPTIONS = "$s0OriginalJavaOptions -Dhttps.proxyHost=$ProxyHost -Dhttps.proxyPort=$ProxyPort -Dhttp.proxyHost=$ProxyHost -Dhttp.proxyPort=$ProxyPort"
    }
    $localGradle = Join-Path $taskRoot '.local/gradle-9.6.0/bin/gradle.bat'
    if (Test-Path -LiteralPath $localGradle) {
        & $localGradle -p $clientRoot :app:assembleDebug --no-daemon --console=plain
    } else {
        Push-Location $clientRoot
        try { & ./gradlew.bat :app:assembleDebug --no-daemon --console=plain } finally { Pop-Location }
    }
    if ($LASTEXITCODE -ne 0) { throw "Baseline build failed with exit code $LASTEXITCODE" }
} finally {
    $env:JAVA_TOOL_OPTIONS = $s0OriginalJavaOptions
    if ($UsePlaceholderFirebase) { Remove-Item -LiteralPath $firebasePath -ErrorAction SilentlyContinue }
}
