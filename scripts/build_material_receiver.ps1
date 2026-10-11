$ErrorActionPreference = 'Stop'
$receiverRoot = Split-Path $PSScriptRoot -Parent
$receiverSource = Join-Path $receiverRoot 'qa/material/receiver'
$receiverBuild = Join-Path $receiverRoot '.local/material-receiver'
$receiverSdk = Join-Path $receiverRoot '.local/android-sdk'
$receiverTools = Join-Path $receiverSdk 'build-tools/36.0.0'
$receiverAndroid = Join-Path $receiverSdk 'platforms/android-37.0/android.jar'
New-Item -ItemType Directory -Force "$receiverBuild/classes", "$receiverBuild/dex" | Out-Null
& javac -encoding UTF-8 -source 8 -target 8 -classpath $receiverAndroid -d "$receiverBuild/classes" (Get-ChildItem "$receiverSource/*.java").FullName
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
& "$receiverTools/d8.bat" --lib $receiverAndroid --output "$receiverBuild/dex" (Get-ChildItem "$receiverBuild/classes" -Recurse -Filter '*.class').FullName
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
& "$receiverTools/aapt2.exe" link -o "$receiverBuild/receiver-unsigned.apk" --manifest "$receiverSource/AndroidManifest.xml" -I $receiverAndroid
if ($LASTEXITCODE -ne 0) { throw 'aapt2 failed' }
Push-Location "$receiverBuild/dex"
try { & jar uf "$receiverBuild/receiver-unsigned.apk" classes.dex } finally { Pop-Location }
& "$receiverTools/zipalign.exe" -f 4 "$receiverBuild/receiver-unsigned.apk" "$receiverBuild/receiver.apk"
$receiverKey = Join-Path $receiverBuild 'qa.jks'
if (-not (Test-Path $receiverKey)) { & keytool -genkeypair -keystore $receiverKey -storepass android -keypass android -alias qa -dname 'CN=QA Delivery' -keyalg RSA -validity 3650 | Out-Null }
& "$receiverTools/apksigner.bat" sign --ks $receiverKey --ks-pass pass:android "$receiverBuild/receiver.apk"
if ($LASTEXITCODE -ne 0) { throw 'sign failed' }
& "$receiverSdk/platform-tools/adb.exe" -s emulator-5554 install -r "$receiverBuild/receiver.apk"
if ($LASTEXITCODE -ne 0) { throw 'receiver install failed' }
