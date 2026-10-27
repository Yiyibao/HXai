"""Actual Flutter export -> entire QA process stop -> reopened page manual verification.

Only the dedicated emulator QA package, synthetic profiles, no provider requests.
Both APKs are independently read back; state and screenshots are archived before stopping.
"""
from pathlib import Path
import argparse, hashlib, json, re, subprocess, time, uuid
ROOT=Path(__file__).resolve().parents[1]
PACKAGE='org.hxsi.agent.nativeqa'
RUNNER=PACKAGE+'.test/me.rerere.rikkahub.feature.agent.AgentNativeTestRunner'
parser=argparse.ArgumentParser();parser.add_argument('--run-id',required=True);parser.add_argument('--native-run',required=True)
parser.add_argument('--serial',default='emulator-5554');args=parser.parse_args()
assert re.fullmatch(r'[\w-]+',args.run_id) and re.fullmatch(r'[\w-]+',args.native_run)
out=ROOT/'HXai_CHANGE_REVIEW/AGENT-MODEL-NATIVE-RESUME-20261012/runs'/args.run_id;out.mkdir(exist_ok=False)
adb=[str(ROOT/'.local/android-sdk/platform-tools/adb.exe'),'-s',args.serial];records=[]
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
sources=[p for base in ['client/app/src','client/flutter_ui/lib','client/flutter_ui/pigeons'] for p in (ROOT/base).rglob('*') if p.is_file()]
sources.append(Path(__file__))
(out/'SOURCE_INPUTS.json').write_text(json.dumps({str(p.relative_to(ROOT)):digest(p) for p in sources},indent=2))
def save(): (out/'TEST_RUNS.json').write_text(json.dumps(records,indent=2))
def run(name,command,accept=lambda p:p.returncode==0):
    start=time.time();result=subprocess.run(command,cwd=ROOT,capture_output=True,timeout=120)
    log=out/(name+'.log');log.write_bytes(('$ '+subprocess.list2cmdline(command)+'\n').encode()+result.stdout+result.stderr)
    passed=accept(result);records.append(dict(name=name,command=command,cwd=str(ROOT),seconds=time.time()-start,exit_code=result.returncode,
        status='PASS' if passed else 'FAIL',log=log.name,sha256=digest(log)));save()
    if not passed:raise SystemExit('FAILED '+name+' '+result.stdout.decode('utf8',errors='replace')[-2000:])
    return result
def archive(name):
    command=adb+['exec-out','run-as',PACKAGE,'tar','--exclude=./app_flutter','--exclude=./app_dxmaker_cache','-cf','-','.']
    result=subprocess.run(command,cwd=ROOT,capture_output=True,timeout=30);file=out/(name+'-SYNTHETIC_STATE.tar');file.write_bytes(result.stdout)
    records.append(dict(name=name+'-archive',command=command,cwd=str(ROOT),exit_code=result.returncode,status='PASS' if result.returncode==0 else 'FAIL',artifact=file.name,sha256=digest(file)));save()
    if result.returncode:raise SystemExit('Archive failed; state preserved')
run('emulator-only',adb+['shell','getprop','ro.kernel.qemu'],lambda p:p.returncode==0 and p.stdout.strip()==b'1')
expected=json.loads((out.parent/args.native_run/'APK_INPUTS.json').read_text())
for package,suffix in [(PACKAGE,'app-x86_64-debug.apk'),(PACKAGE+'.test','app-debug-androidTest.apk')]:
    remote=run(package+'-path',adb+['shell','pm','path',package]).stdout.decode().strip().splitlines()[0];assert remote.startswith('package:')
    file=out/(package+'.apk');run(package+'-readback',adb+['pull',remote.removeprefix('package:'),str(file)])
    assert digest(file)==next(h for p,h in expected.items() if p.endswith(suffix))
for theme,font in [('light','1'),('dark','2')]:
    fixture=str(uuid.uuid4());label=theme+'-'+font;folder='cache/usage-recovery-surface-'+fixture
    prepare=adb+['shell','am','instrument','-w','-r','-e','class','me.rerere.rikkahub.feature.agent.AgentUsageRecoverySurfaceProbe#prepare',
        '-e','fixture',fixture,'-e','theme',theme,'-e','font',font,RUNNER]
    log=out/(label+'-prepare.log')
    with log.open('wb') as stream:
        process=subprocess.Popen(prepare,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT);ready=False
        try:
            end=time.monotonic()+90
            while time.monotonic()<end:
                p=subprocess.run(adb+['exec-out','run-as',PACKAGE,'cat',folder+'/ready'],capture_output=True,timeout=5)
                if p.returncode==0 and p.stdout.strip()==b'ui-exported':ready=True;break
                if process.poll() is not None:break
                time.sleep(.25)
            if not ready:raise RuntimeError('Actual UI never reached durable verified export: '+label)
            archive(label+'-before-stop');run(label+'-force-stop',adb+['shell','am','force-stop',PACKAGE]);process.wait(timeout=15)
            run(label+'-old-process-stopped',adb+['shell','pidof',PACKAGE],lambda p:not p.stdout.strip() and p.returncode in (0,1))
        finally:
            if process.poll() is None:
                subprocess.run(adb+['shell','am','force-stop',PACKAGE],capture_output=True,timeout=15);process.wait(timeout=15)
    records.append(dict(name=label+'-actual-process-stop',command=prepare,cwd=str(ROOT),exit_code=process.returncode,
        status='PASS' if ready else 'FAIL',log=log.name,sha256=digest(log),fixture=fixture));save()
    verify=adb+['shell','am','instrument','-w','-r','-e','class','me.rerere.rikkahub.feature.agent.AgentUsageRecoverySurfaceProbe#verify','-e','fixture',fixture,RUNNER]
    # JUnit assertions bind the actual UI/file evidence. Android routes println to
    # logcat, not consistently to am instrument's result stream.
    run(label+'-reopened-actual-ui',verify,lambda p:p.returncode==0 and b'OK (1 test)' in p.stdout and b'FAILURES!!!' not in p.stdout and b'INSTRUMENTATION_FAILED' not in p.stdout)
    archive(label+'-after-verification')
    print(label+' actual reopened UI PASS',flush=True)
print('Two actual UI process restart/manual verification cases PASS',flush=True)
