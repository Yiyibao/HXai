import subprocess,json
from pathlib import Path
qa=Path('qa/comprehensive-review-20261008')
r=subprocess.run([r'.local/android-sdk/platform-tools/adb.exe','-s','emulator-5554','shell','run-as','org.mathstudy.agent.debug','test','-f','cache/document-service-test-credentials.json'],capture_output=True)
(qa/'document-live-precondition.json').write_text(json.dumps({'dedicated_synthetic_credentials_present':r.returncode==0,'raw_credential_read':False,'live_parse_executed':False},indent=2),encoding='utf-8')
