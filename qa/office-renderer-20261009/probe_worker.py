"""Run the real offline LibreOffice image on synthetic fixtures before service deployment."""
from pathlib import Path
import subprocess
key=str(Path.home()/'.ssh/id_ed25519_tencent_blog')
script='''import os,json,shutil,hashlib,subprocess
from pathlib import Path
stage=Path('/opt/mathdoc/office-staging-20261009')
image=(stage/'image-id.txt').read_text().strip()
version=(stage/'renderer-version.txt').read_text().strip()
results=[]
for extension in ['docx','xlsx','pptx']:
    root=stage/('probe-'+extension); root.mkdir(exist_ok=True)
    source=root/'input'; source.mkdir(exist_ok=True)
    output=root/'output'; output.mkdir(exist_ok=True); os.chown(output,1000,1000); os.chmod(output,0o700)
    for name in ['error.json','output.pdf','manifest.json']:
        (output/name).unlink(missing_ok=True)
    file=source/('source.'+extension); shutil.copyfile(stage/('samples/rich-source.'+extension),file)
    options={'operation':'office_pdf','sourceSha256':hashlib.sha256(file.read_bytes()).hexdigest(),'extension':extension,'rendererVersion':version,'renderOptions':{'scope':'print_layout','includeHiddenSlides':False,'includeNotes':False}}
    (source/'request.json').write_text(json.dumps(options))
    args=['docker','run','--rm','--network','none','--read-only','--user','1000:1000','--cap-drop','ALL','--security-opt','no-new-privileges','--pids-limit','192','--memory','4g','--memory-swap','4g','--cpus','4','--log-driver','none','--tmpfs','/tmp:rw,noexec,nosuid,size=384m,mode=1777','--mount',f'type=bind,src={source},dst=/input,readonly','--mount',f'type=bind,src={output},dst=/output',image]
    run=subprocess.run(args,timeout=320,capture_output=True,text=True)
    if run.returncode:
        print(extension,run.stdout,run.stderr,(output/'error.json').read_text() if (output/'error.json').exists() else 'no error record',flush=True)
        raise SystemExit(1)
    subprocess.run(['docker','run','--rm','--network','none','--read-only','--user','1000:1000','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','768m','--log-driver','none','--mount',f'type=bind,src={output},dst=/output,readonly','--entrypoint','/usr/bin/python3',image,'-m','office_worker.check_output'],check=True,timeout=60)
    manifest=json.loads((output/'manifest.json').read_text()); results.append(manifest)
    print(extension,manifest['pageCount'],manifest['warnings'],flush=True)
(stage/'probe-results.json').write_text(json.dumps(results,indent=2)); os.chmod(stage/'probe-results.json',0o644)
'''
subprocess.run(['ssh','-i',key,'ubuntu@154.222.25.150','sudo python3 -'],input=script.encode(),check=True)
