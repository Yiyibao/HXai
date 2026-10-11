import subprocess,json,re,hashlib
from pathlib import Path
root=Path(__file__).resolve().parents[2]
qa=Path(__file__).parent
adb=str(root/'.local/android-sdk/platform-tools/adb.exe')
files={'s1-real-teaching.md':'teaching-and-approval.log','s2-real-memory.json':'memory-real.log','s2-real-followup.md':'memory-real.log','s3-real-teaching.md':'learning-loop-real.log','material-real-model.md':'material-real.log','material-evidence/delivery-receipts.txt':'runtime/MaterialDeliveryTest.log','review-delivery/delivery-receipts.txt':'runtime/ProjectReviewDeliveryTest.log'}
out=qa/'fixtures';out.mkdir(exist_ok=True)
manifest=[]
for name,logname in files.items():
    log=qa/logname
    if not log.exists() or not re.search(r'OK \(\d+ tests?\)',log.read_text(encoding='utf-8-sig')): continue
    stat=subprocess.run([adb,'-s','emulator-5554','exec-out','run-as','org.mathstudy.agent.debug','stat','-c','%Y','cache/'+name],capture_output=True)
    try: modified=int(stat.stdout.strip())
    except ValueError: continue
    if modified+3 < log.stat().st_ctime: continue
    r=subprocess.run([adb,'-s','emulator-5554','exec-out','run-as','org.mathstudy.agent.debug','cat','cache/'+name],capture_output=True)
    if r.returncode==0 and len(r.stdout)<=2*1024*1024:
        target=out/name.replace('/','-')
        target.write_bytes(r.stdout)
        manifest.append({'name':target.name,'source_log':logname,'bytes':len(r.stdout),'device_modified_epoch':modified,'sha256':hashlib.sha256(r.stdout).hexdigest()})
(qa/'synthetic-fixture-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print(json.dumps(manifest,indent=2))
