"""B-only synthetic Android mathematics runtime. No A emulator/shared builds/models.
Archive the prior QA package before installing; read back the exact installed APKs.
"""
from pathlib import Path
import argparse, hashlib, json, re, subprocess, time
ROOT=Path(__file__).resolve().parents[1]
PACKAGE='org.hxsi.agent.nativeqa'
RUNNER=PACKAGE+'.test/me.rerere.rikkahub.feature.agent.AgentNativeTestRunner'
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--worktree',type=Path,required=True);p.add_argument('--run-dir',type=Path,required=True);p.add_argument('--serial',default='emulator-5584');p.add_argument('--apk-dir',type=Path);a=p.parse_args()
    assert a.serial!='emulator-5554','B must not touch A emulator'
    work=a.worktree.resolve();out=a.run_dir.resolve();out.mkdir(parents=True,exist_ok=False)
    adb=[str(ROOT/'.local/android-sdk/platform-tools/adb.exe'),'-s',a.serial];records=[]
    def run(name,command,accept=lambda r:r.returncode==0,timeout=180,binary=False):
        start=time.monotonic();r=subprocess.run(list(map(str,command)),cwd=ROOT,capture_output=True,timeout=timeout)
        log=out/(name+('.bin' if binary else '.log'));log.write_bytes(r.stdout if binary else r.stdout+b'\n'+r.stderr)
        passed=accept(r);records.append(dict(name=name,command=list(map(str,command)),cwd=str(ROOT),exitCode=r.returncode,status='PASS' if passed else 'FAIL',seconds=time.monotonic()-start,log=log.name,sha256=digest(log)))
        (out/'TEST_RUNS.json').write_text(json.dumps(records,indent=2));print(name,records[-1]['status'],flush=True)
        if not passed:raise SystemExit(r.stdout.decode('utf8',errors='replace')[-4000:]+r.stderr.decode('utf8',errors='replace'))
        return r.stdout
    run('emulator-only',adb+['shell','getprop','ro.kernel.qemu'],lambda r:r.returncode==0 and r.stdout.strip()==b'1')
    old=run('prior-qa-path',adb+['shell','pm','path',PACKAGE])
    if b'package:' in old:
        run('stop-prior-qa',adb+['shell','am','force-stop',PACKAGE])
        run('prior-SYNTHETIC_STATE',adb+['exec-out','run-as',PACKAGE,'tar','--exclude=./app_flutter','--exclude=./app_dxmaker_cache','-cf','-','.'],binary=True)
    apks=[work/'client/app/build/outputs/apk/debug/app-x86_64-debug.apk',work/'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    if a.apk_dir is not None:apks=[a.apk_dir.resolve()/p.name for p in apks]
    sources={p.relative_to(work).as_posix():digest(p) for base in ['client/app/src','client/ai/src'] for p in (work/base).rglob('*') if p.is_file()}
    (out/'SOURCE_INPUTS.json').write_text(json.dumps(sources,indent=2))
    (out/'APK_INPUTS.json').write_text(json.dumps({str(p):digest(p) for p in apks},indent=2))
    for package,apk in zip([PACKAGE,PACKAGE+'.test'],apks):
        run('install-'+package,adb+['install','-r',apk],lambda r:r.returncode==0 and b'Success' in r.stdout)
        path=run('path-'+package,adb+['shell','pm','path',package]).decode().strip().splitlines()[0];assert path.startswith('package:')
        actual=out/(package+'.apk');run('readback-'+package,adb+['pull',path[8:],actual]);assert digest(actual)==digest(apk)
    classes='me.rerere.rikkahub.feature.math.MathVerificationRuntimeTest,me.rerere.rikkahub.feature.math.RationalMathRuntimeTest,me.rerere.rikkahub.feature.math.InequalityRuntimeTest,me.rerere.rikkahub.feature.math.EquationRuntimeTest'
    run('actual-math-tool-runtime',adb+['shell','am','instrument','-w','-r','-e','class',classes,RUNNER],lambda r:r.returncode==0 and re.search(rb'OK \(\d+ tests?\)',r.stdout) and b'FAILURES!!!' not in r.stdout and b'INSTRUMENTATION_FAILED' not in r.stdout,600)
    run('actual-SYNTHETIC_STATE',adb+['exec-out','run-as',PACKAGE,'tar','--exclude=./app_flutter','--exclude=./app_dxmaker_cache','-cf','-','.'],binary=True)
    run('persisted-domain-evidence',adb+['exec-out','run-as',PACKAGE,'cat','cache/b3d-equation-evidence/original-domain-solutions.json'])
    print('Actual Android math Tool execution and original-domain readback PASS',flush=True)
if __name__=='__main__':main()
