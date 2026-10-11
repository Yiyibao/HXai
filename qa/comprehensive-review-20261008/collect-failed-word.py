import subprocess,re,json,zipfile,hashlib
from pathlib import Path
qa=Path('qa/comprehensive-review-20261008')
adb=str(Path('.local/android-sdk/platform-tools/adb.exe').resolve())
identifier='1b065771-a234-49aa-9099-909db839762c'
r=subprocess.run([adb,'-s','emulator-5554','exec-out','run-as','org.mathstudy.agent.debug','find','files/materials','-name',identifier+'.docx'],capture_output=True,check=True)
paths=r.stdout.decode().splitlines()
assert len(paths)==1 and re.fullmatch(r'files/materials/[a-z0-9-]+/'+identifier+r'\.docx',paths[0])
r=subprocess.run([adb,'-s','emulator-5554','exec-out','run-as','org.mathstudy.agent.debug','cat',paths[0]],capture_output=True,check=True)
output=qa/'word-real-failed.docx'; output.write_bytes(r.stdout)
with zipfile.ZipFile(output) as z:
 xml=z.read('word/document.xml').decode('utf-8')
 (qa/'word-real-failed-document.xml').write_text(xml,encoding='utf-8')
 print(xml)
