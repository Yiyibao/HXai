"""Deploy the tested conversion integration with guarded idle queue and code/config rollback."""
from pathlib import Path
import subprocess,tarfile
root=Path(__file__).resolve().parents[2]; service=root/'server/document-service'; evidence=Path(__file__).parent
files=['api/main.py','api/store.py','api/office.py','api/office_output.py','dispatcher/main.py']
archive=evidence/'office-service-update.tar.gz'
with tarfile.open(archive,'w:gz') as tar:
    for name in files: tar.add(service/name,arcname=name)
key=str(Path.home()/'.ssh/id_ed25519_tencent_blog'); host='ubuntu@154.222.25.150'
subprocess.run(['scp','-i',key,str(archive),host+':/home/ubuntu/mathdoc-office-service-20261009.tar.gz'],check=True)
script=r'''import os,json,tarfile,shutil,subprocess,time,sqlite3,urllib.request
from pathlib import Path
service=Path('/opt/mathdoc/service'); stage=Path('/opt/mathdoc/office-staging-20261009')
backup=Path('/opt/mathdoc/office-backups/20261009-1'); backup.mkdir(parents=True,exist_ok=False); os.chmod(backup,0o700)
files=%r
env=Path('/etc/mathdoc/worker.env'); ready=Path('/var/lib/mathdoc/office-ready.json')
for name in files:
    source=service/name
    if source.exists():
        target=backup/name; target.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(source,target)
shutil.copy2(env,backup/'worker.env')
if ready.exists(): shutil.copy2(ready,backup/'office-ready.json')
def idle():
    db=sqlite3.connect('/var/lib/mathdoc/tasks.sqlite3')
    try: return db.execute("SELECT count(*) FROM jobs WHERE state IN ('QUEUED','RUNNING')").fetchone()[0]==0
    finally: db.close()
if not idle(): raise SystemExit('deployment_deferred_active_queue_no_services_changed')
subprocess.run(['systemctl','stop','mathdoc-api'],check=True)
try:
    if not idle(): raise RuntimeError('queue_changed_deployment_deferred')
    subprocess.run(['systemctl','stop','mathdoc-dispatcher'],check=True)
    with tarfile.open('/home/ubuntu/mathdoc-office-service-20261009.tar.gz') as tar:
        assert set(tar.getnames())==set(files)
        for member in tar:
            assert member.isfile() and not member.issym() and not member.islnk()
        tar.extractall(stage/'service-update',filter='data')
    for name in files:
        destination=service/name; temporary=destination.with_suffix('.office-update')
        shutil.copyfile(stage/'service-update'/name,temporary); os.chmod(temporary,0o644); os.replace(temporary,destination)
    image=(stage/'image-id.txt').read_text().strip(); assert image.startswith('sha256:') and len(image)==71
    lines=[line for line in env.read_text().splitlines() if not line.startswith('MATHDOC_OFFICE_IMAGE=')]
    temporary=env.with_suffix('.office-update'); temporary.write_text('\n'.join(lines)+ '\nMATHDOC_OFFICE_IMAGE='+image+'\n'); os.chmod(temporary,0o644); os.replace(temporary,env)
    version=(stage/'renderer-version.txt').read_text().strip()
    temporary=ready.with_suffix('.office-update'); temporary.write_text(json.dumps({'rendererVersion':version,'imageId':image,'verifiedSyntheticFormats':['docx','xlsx','pptx']})); os.chmod(temporary,0o644); os.replace(temporary,ready)
    subprocess.run(['systemctl','start','mathdoc-dispatcher','mathdoc-api'],check=True)
    for attempt in range(30):
        try:
            health=json.load(urllib.request.urlopen('http://127.0.0.1:8766/health',timeout=2))
            if health['status']=='ok': break
        except Exception:
            if attempt==29: raise
            time.sleep(0.5)
    subprocess.run(['systemctl','is-active','mathdoc-api','mathdoc-dispatcher'],check=True)
    print(json.dumps({'deployed':True,'rendererVersion':version,'imageId':image,'idleQueueBeforeRestart':True,'rollbackDirectory':str(backup)}))
except BaseException:
    subprocess.run(['systemctl','stop','mathdoc-api','mathdoc-dispatcher'],check=False)
    for name in files:
        if (backup/name).exists(): shutil.copy2(backup/name,service/name)
        else: (service/name).unlink(missing_ok=True)
    shutil.copy2(backup/'worker.env',env)
    if (backup/'office-ready.json').exists(): shutil.copy2(backup/'office-ready.json',ready)
    else: ready.unlink(missing_ok=True)
    subprocess.run(['systemctl','start','mathdoc-dispatcher','mathdoc-api'],check=True)
    print('deployment_rolled_back')
    raise
''' % files
subprocess.run(['ssh','-i',key,host,'sudo python3 -'],input=script.encode(),check=True)
