"""Synthetic API26 Chat -> owned Linux Skill container -> independent receipt readback.

Archives QA state before clearing only org.hxsi.agent.nativeqa. Starts/stops only
its own loopback MCP service; needs the previously owned private daemon running.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import socket
import subprocess
import tarfile
import time
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[1]
PKG='org.hxsi.agent.nativeqa'
WSL=['wsl.exe','--distribution','HXai-B06-Linux-20261021','--user','root','--exec']
IMAGE='sha256:69e69785e7156672be426d8e758cfe1becdcfabd7583ae9ca9a88bc41e3c7433'

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run-dir',type=Path,required=True)
    p.add_argument('--port',type=int,default=0,help='New loopback port per qualification; zero selects an available port')
    p.add_argument('--qa-main',type=Path,default=ROOT/'client/app/build/outputs/apk/debug/app-x86_64-debug.apk')
    p.add_argument('--qa-test',type=Path,default=ROOT/'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    args=p.parse_args();out=args.run_dir.resolve();out.mkdir(parents=True,exist_ok=False)
    adb=[str(ROOT/'.local/android-sdk/platform-tools/adb.exe'),'-s','emulator-5584']
    records=[]
    def run(name, command, predicate=lambda r:r.returncode==0, binary=False):
        r=subprocess.run(list(map(str,command)),cwd=ROOT,capture_output=True,timeout=180)
        (out/(name+('.tar' if binary else '.log'))).write_bytes(r.stdout if binary else r.stdout+r.stderr)
        records.append(dict(name=name,command=['<synthetic-token>' if value==token else str(value) for value in command],cwd=str(ROOT),exit_code=r.returncode,passed=bool(predicate(r))))
        (out/'TEST_RUNS.json').write_text(json.dumps(records,indent=2))
        if not records[-1]['passed']:raise RuntimeError(name+' failed; inspect raw log')
        return r.stdout
    token='synthetic-a04-sandbox-chat-'+uuid.uuid4().hex
    owner=json.loads(subprocess.check_output(WSL+['/bin/cat','/etc/hxai-b06-owned-instance.json']))
    if owner['owner_nonce']!='22d0404ee58f4d24b3cb8bd911d7d1be':raise ValueError('Wrong Linux owner')
    if b'hxai-b06-api26-x64-20261021' not in run('avd',adb+['emu','avd','name']):raise ValueError('Wrong AVD')
    if run('api',adb+['shell','getprop','ro.build.version.sdk']).strip()!=b'26':raise ValueError('Wrong API')
    aapt=sorted((ROOT/'.local/android-sdk/build-tools').glob('*/aapt2.exe'))[-1]
    apks={PKG:args.qa_main.resolve(),PKG+'.test':args.qa_test.resolve()}
    hashes={}
    for package,path in apks.items():
        badging=run('apk-identity-'+package,[aapt,'dump','badging',path])
        if ("package: name='"+package+"'").encode() not in badging:raise ValueError('Wrong APK identity')
        hashes[package]=hashlib.sha256(path.read_bytes()).hexdigest()
        if package.endswith('.test'):run('install-only-qa-test',adb+['install','-r',path])
        remote=run('installed-path-'+package,adb+['shell','pm','path',package]).decode().strip().splitlines()[0].removeprefix('package:')
        actual=run('installed-sha-'+package,adb+['shell','sha256sum',remote]).decode().split()[0]
        if actual!=hashes[package]:raise ValueError('Installed APK bytes differ')
    (out/'APK_INPUTS.json').write_text(json.dumps(hashes,indent=2))
    with socket.socket() as probe:
        probe.bind(('127.0.0.1',args.port));port=probe.getsockname()[1]
    linux='/opt/hxai-a04/chat-'+uuid.uuid4().hex
    run('mkdir',WSL+['/bin/mkdir','-p',linux])
    blob=io.BytesIO();source={}
    with tarfile.open(fileobj=blob,mode='w') as tar:
        for path in [ROOT/'server/skill-sandbox/sandbox_service.py',ROOT/'server/skill-sandbox/examples/sum.py']:
            tar.add(path,arcname=path.name);source[path.name]=hashlib.sha256(path.read_bytes()).hexdigest()
        info=tarfile.TarInfo('synthetic-token');info.size=len(token);info.mode=0o600;tar.addfile(info,io.BytesIO(token.encode()))
        # Synthetic qualification only: HTTP status/peer diagnostics, never bodies or headers.
        launcher=b'''import sandbox_service as s
original=s.handler
def diagnostic_handler(sandbox):
    cls=original(sandbox)
    def status(self, code, message=None):
        print("QA_HTTP_STATUS", code, flush=True)
        return super(cls,self).send_response(code,message)
    cls.send_response=status
    return cls
s.handler=diagnostic_handler
s.main()
'''
        info=tarfile.TarInfo('diagnostic_launcher.py');info.size=len(launcher);tar.addfile(info,io.BytesIO(launcher))
    subprocess.run(WSL+['/bin/tar','-xf','-','-C',linux],input=blob.getvalue(),check=True)
    (out/'SOURCE_INPUTS.json').write_text(json.dumps(source,indent=2))
    cmd=WSL+['/usr/bin/python3',linux+'/diagnostic_launcher.py','--root',linux+'/data','--image',IMAGE,'--script-id','sum','--script',linux+'/sum.py',
        '--script-sha256',source['sum.py'],'--docker-host','unix:///run/hxai-b06/docker.sock','--token-file',linux+'/synthetic-token','--port',str(port)]
    log=(out/'service.log').open('wb');process=subprocess.Popen(cmd,stdout=log,stderr=subprocess.STDOUT)
    try:
        ready=False
        for _ in range(80):
            try:
                request=urllib.request.Request('http://127.0.0.1:'+str(port)+'/mcp',data=b'{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}',headers={'Content-Type':'application/json','Authorization':'Bearer '+token,'X-HXai-Profile':hashlib.sha256(b'qa-readiness').hexdigest()})
                with urllib.request.urlopen(request,timeout=1) as response:ready=response.status==200
                if ready:break
            except (OSError,ValueError):pass
            if process.poll() is not None:break
            time.sleep(.25)
        if not ready:raise RuntimeError('Owned service not ready; no app state cleared')
        run('stop-qa',adb+['shell','am','force-stop',PKG])
        run('archive-before',adb+['exec-out','run-as',PKG,'tar','--exclude=./app_flutter','--exclude=./app_dxmaker_cache','-cf','-','.'],binary=True)
        run('clear-only-qa',adb+['shell','pm','clear',PKG],lambda r:r.returncode==0 and b'Success' in r.stdout)
        run('reverse',adb+['reverse','tcp:'+str(port),'tcp:'+str(port)])
        command=adb+['shell','am','instrument','-w','-r','-e','class','me.rerere.rikkahub.feature.agent.SandboxMcpChatStoryTest',
            '-e','code_sha256',source['sum.py'],'-e','sandbox_token',token,'-e','sandbox_port',str(port),PKG+'.test/me.rerere.rikkahub.feature.agent.AgentNativeTestRunner']
        run('actual-chat',command,lambda r:r.returncode==0 and re.search(rb'OK \(1 test\)',r.stdout) and b'FAILURES!!!' not in r.stdout)
        result=run('independent-server-journal',WSL+['/usr/bin/python3','-c',
            'import sqlite3,json;d=sqlite3.connect('+repr(linux+'/data/journal.sqlite')+');rows=d.execute("SELECT state,receipt FROM calls").fetchall();assert len(rows)==1 and rows[0][0]=="SUCCEEDED";v=json.loads(rows[0][1]);assert json.loads(v["output_json"])["sum"]==6;print(json.dumps(v))'])
        (out/'RESULT.json').write_text(json.dumps(dict(status='PASS',linux=linux,receipt=json.loads(result)),indent=2))
        run('final-state',adb+['exec-out','run-as',PKG,'tar','--exclude=./app_flutter','--exclude=./app_dxmaker_cache','-cf','-','.'],binary=True)
    finally:
        subprocess.run(adb+['reverse','--remove','tcp:'+str(port)],capture_output=True)
        # Terminate only our actual Linux service PID selected by exact script path.
        subprocess.run(WSL+['/usr/bin/pkill','-TERM','-f',linux+'/diagnostic_launcher.py'],capture_output=True)
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=10)
        log.close()
    print('PASS: actual Android task + isolated Linux Python + independent readback; one server invocation')

if __name__=='__main__':main()
