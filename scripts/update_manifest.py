with open('client/app/src/main/AndroidManifest.xml', 'r', encoding='utf-8') as f:
    content = f.read()

target = '        android:resource="@xml/shortcuts" />\n    </activity>'
if target not in content:
    target = '        android:resource="@xml/shortcuts" />\r\n    </activity>'

insertion = '''    <activity
      android:name=".flutter.StudyFlutterActivity"
      android:configChanges="orientation|keyboardHidden|keyboard|screenSize|locale|layoutDirection|fontScale|screenLayout|density|uiMode"
      android:hardwareAccelerated="true"
      android:windowSoftInputMode="adjustResize"
      android:exported="true"
      android:theme="@style/Theme.Rikkahub" />'''

if target in content:
    new_content = content.replace(target, target + '\n' + insertion)
    with open('client/app/src/main/AndroidManifest.xml', 'w', encoding='utf-8') as f:
        f.write(new_content)
    print("SUCCESS")
else:
    print("TARGET NOT FOUND")
