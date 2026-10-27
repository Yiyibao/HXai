"""QA-only process persistence verification; archives first, never clears data.

Requires a separately built nativeqa/test APK. Only the owned synthetic emulator;
no model, remote endpoint, private data, install dependencies or production app.
"""
from pathlib import Path
import argparse, subprocess, json, hashlib, uuid, re, time

ROOT=Path(__file__).resolve().parents[1]
PKG='org.hxsi.agent.nativeqa'
RUNNER=PKG+'.test/me.rerere.rikkahub.feature.agent.AgentNativeTestRunner'

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--serial',required=True)
    p.add_argument('--run-dir',required=True)
    p.add_argument('--install',action='store_true')
    p.add_argument('--qa-main',type=Path,default=ROOT/'HXai_CHANGE_REVIEW/A04-OAUTH-A05-COST-20261022/tested-apks/app-x86_64-debug.apk')
    p.add_argument('--qa-test',type=Path,default=ROOT/'HXai_CHANGE_REVIEW/A04-OAUTH-A05-COST-20261022/tested-apks/app-debug-androidTest.apk')
    p.add_argument('--ownership',type=Path,
        help='Explicit dedicated emulator record; actual serial/AVD/API/ABI are checked before mutations')
    p.add_argument('--mode',default='money_oauth',choices=['money_oauth'])
    a=p.parse_args()
    if not re.fullmatch(r'emulator-\d+',a.serial):
        p.error('Only an explicitly owned emulator is allowed')
    ownership=None
    if a.ownership:
        ownership=json.loads(a.ownership.read_text(encoding='utf-8-sig'))
        if (ownership.get('serial')!=a.serial or ownership.get('created_this_batch') is not True or
            type(ownership.get('api')) is not int or ownership['api']<26 or
            not re.fullmatch(r'[A-Za-z0-9_-]+',ownership.get('avd','')) or
            not re.fullmatch(r'[A-Za-z0-9_-]+',ownership.get('abi',''))):
            p.error('Ownership record does not authorize this emulator')
    out=Path(a.run_dir).resolve();out.mkdir(parents=True,exist_ok=False)
    if ownership is not None:
        (out/'OWNERSHIP_INPUT.json').write_bytes(a.ownership.read_bytes())
    (out/'RUNNER_INPUT.py').write_bytes(Path(__file__).read_bytes())
    sources=['scripts/verify_a045_persistence.py',
        'client/app/src/main/java/me/rerere/rikkahub/feature/agent/ExtensionInstallationStore.kt',
        'client/app/src/main/java/me/rerere/rikkahub/feature/agent/ExtensionOAuth.kt', 'client/app/src/main/java/me/rerere/rikkahub/feature/agent/TrustedCost.kt', 'client/app/src/main/java/me/rerere/rikkahub/feature/agent/AgentCostLedger.kt', 'client/app/src/main/java/me/rerere/rikkahub/feature/agent/AgentTaskStore.kt',
        'client/app/src/androidTest/java/me/rerere/rikkahub/feature/agent/OAuthCostPersistenceProbe.kt']
    (out/'SOURCE_INPUTS.json').write_text(json.dumps({s:hashlib.sha256((ROOT/s).read_bytes()).hexdigest() for s in sources},indent=2),encoding='utf8')
    adb=[str(ROOT/'.local/android-sdk/platform-tools/adb.exe'),'-s',a.serial]
    records=[]
    def run(name,command,accept=lambda r:r.returncode==0,binary=False):
        r=subprocess.run(list(map(str,command)),cwd=ROOT,capture_output=True,timeout=180)
        f=out/(name+('.tar' if binary else '.log'));f.write_bytes(r.stdout if binary else r.stdout+r.stderr)
        ok=accept(r)
        records.append(dict(name=name,command=list(map(str,command)),cwd=str(ROOT),exit_code=r.returncode,status='PASS' if ok else 'FAIL',log=f.name,sha256=hashlib.sha256(f.read_bytes()).hexdigest()))
        (out/'TEST_RUNS.json').write_text(json.dumps(records,indent=2),encoding='utf8')
        if not ok: raise SystemExit('FAILED: '+name)
        return r
    run('emulator',adb+['shell','getprop','ro.kernel.qemu'],lambda r:r.returncode==0 and r.stdout.strip()==b'1')
    expected_avd=ownership['avd'] if ownership else 'hxai-p2-math-20261022'
    run('avd',adb+['emu','avd','name'],lambda r:r.returncode==0 and
        r.stdout.decode().splitlines()[:1]==[expected_avd])
    if ownership is not None:
        run('api',adb+['shell','getprop','ro.build.version.sdk'],lambda r:r.returncode==0 and r.stdout.strip()==str(ownership['api']).encode())
        run('abi',adb+['shell','getprop','ro.product.cpu.abi'],lambda r:r.returncode==0 and r.stdout.strip()==ownership['abi'].encode())
    run('boot',adb+['shell','getprop','sys.boot_completed'],lambda r:r.returncode==0 and r.stdout.strip()==b'1')
    run('stop-previous',adb+['shell','am','force-stop',PKG])
    packages=run('packages',adb+['shell','pm','list','packages',PKG]).stdout
    if b'package:'+PKG.encode() in packages.splitlines():
        run('previous-state',adb+['exec-out','run-as',PKG,'tar','--exclude=./app_flutter','--exclude=./app_dxmaker_cache','-cf','-','.'],binary=True)
    hashes={}
    aapt=sorted((ROOT/'.local/android-sdk/build-tools').glob('*/aapt2.exe'))[-1]
    for package,path in [(PKG,a.qa_main.resolve()),(PKG+'.test',a.qa_test.resolve())]:
        hashes[str(path.relative_to(ROOT))]=hashlib.sha256(path.read_bytes()).hexdigest()
        run('identity-'+package,[aapt,'dump','badging',path],lambda r:r.returncode==0 and ("package: name='"+package+"'").encode() in r.stdout)
        if a.install: run('install-'+package,adb+['install','-r',path],lambda r:r.returncode==0 and b'Success' in r.stdout)
        remote=run('path-'+package,adb+['shell','pm','path',package]).stdout.decode().strip().splitlines()[0]
        assert remote.startswith('package:')
        expected=hashes[str(path.relative_to(ROOT))]
        run('installed-hash-'+package,adb+['shell','sha256sum',remote.removeprefix('package:')],
            lambda r:r.returncode==0 and r.stdout.decode().split()[0]==expected)
    (out/'APK_INPUTS.json').write_text(json.dumps(hashes,indent=2),encoding='utf8')
    fixture=str(uuid.uuid4())
    def invoke(method):
        return adb+['shell','am','instrument','-w','-r','-e','class','me.rerere.rikkahub.feature.agent.OAuthCostPersistenceProbe#'+method,'-e','fixture',fixture,'-e','mode',a.mode,RUNNER]
    passed=lambda r:r.returncode==0 and re.search(rb'OK \(1 test\)',r.stdout) is not None and b'FAILURES!!!' not in r.stdout
    command=invoke('prepare');log=out/'prepare.log'
    ready=False
    with log.open('wb') as stream:
        process=subprocess.Popen(command,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+40
            while time.monotonic()<deadline:
                marker=subprocess.run(adb+['exec-out','run-as',PKG,'cat','no_backup/oauth-cost-probe-'+fixture+'.txt.ready'],capture_output=True,timeout=5)
                if marker.returncode==0 and marker.stdout.decode().strip()==a.mode:
                    ready=True;break
                if process.poll() is not None:break
                time.sleep(.25)
            assert ready,'No durable ready marker; preserve state and inspect prepare.log'
            run('old-process-alive',adb+['shell','pidof',PKG],lambda r:r.returncode==0 and bool(r.stdout.strip()))
            run('force-stop',adb+['shell','am','force-stop',PKG])
            process.wait(timeout=20)
        finally:
            if process.poll() is None:
                subprocess.run(adb+['shell','am','force-stop',PKG],capture_output=True,timeout=15)
                process.wait(timeout=20)
    records.append(dict(name='prepare-and-actual-exit',command=command,cwd=str(ROOT),exit_code=process.returncode,status='PASS' if ready else 'FAIL',log=log.name,sha256=hashlib.sha256(log.read_bytes()).hexdigest(),note='PASS is durable readiness plus live PID and host stop, not instrumentation exit status'))
    (out/'TEST_RUNS.json').write_text(json.dumps(records,indent=2),encoding='utf8')
    run('old-process-stopped',adb+['shell','pidof',PKG],lambda r:r.returncode in (0,1) and not r.stdout.strip())
    run('reopen-verify',invoke('verify'),passed)
    run('final-stop',adb+['shell','am','force-stop',PKG])
    run('final-state',adb+['exec-out','run-as',PKG,'tar','--exclude=./app_flutter','--exclude=./app_dxmaker_cache','-cf','-','.'],binary=True)
    print(a.mode,'PASS; real force-stop, no data cleared')

if __name__=='__main__':main()
