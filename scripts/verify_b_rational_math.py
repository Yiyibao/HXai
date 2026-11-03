"""Compile and exercise the real pure-Kotlin verifier offline; exact Fraction expectations.
No installs, network, Android data, models or private inputs. This is CLI, not App E2E.
"""
import argparse, hashlib, json, os
from pathlib import Path
from fractions import Fraction
import shutil, subprocess, sys
try:
    from project_storage import enforce
except ModuleNotFoundError as error:
    if error.name != "project_storage":
        raise
    from scripts.project_storage import enforce

ROOT=Path(__file__).resolve().parents[1]
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--worktree',type=Path,required=True);parser.add_argument('--run-dir',type=Path,required=True);parser.add_argument('--cases',type=Path,required=True)
    a=parser.parse_args();enforce(a.worktree.resolve());folder=a.run_dir.resolve();folder.mkdir(parents=True,exist_ok=False)
    lib=ROOT/'.local/gradle-9.6.0/lib'
    deps=[lib/name for name in ['kotlin-stdlib-2.3.21.jar','kotlinx-coroutines-core-jvm-1.10.2.jar','kotlinx-serialization-core-jvm-1.11.0.jar','kotlinx-serialization-json-jvm-1.11.0.jar','annotations-24.0.1.jar']]
    assert all(p.is_file() for p in deps)
    source=a.worktree/'client/app/src/main/java/me/rerere/rikkahub/feature/math'
    files=[p for p in source.glob('*.kt') if p.name!='MathTools.kt']
    smoke=ROOT/'HXai_CHANGE_REVIEW/B-RATIONAL-MATH-20261014/smoke/RationalMathCli.kt'
    files.append(smoke);cp=os.pathsep.join(map(str,deps));jar=folder/'verifier.jar';java=shutil.which('java');assert java
    commands=[]
    def run(label,cmd,**kw):
        result=subprocess.run(cmd,cwd=ROOT,capture_output=True,timeout=180,**kw)
        log=folder/(label+'.log');log.write_bytes(result.stdout+b'\n'+result.stderr)
        commands.append(dict(name=label,command=cmd,cwd=str(ROOT),exitCode=result.returncode,log=log.name,sha256=digest(log)))
        (folder/'COMMANDS.json').write_text(json.dumps(commands,indent=2));result.check_returncode();return result.stdout
    run('compile',[java,'-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(jar),*map(str,files)])
    cases=json.loads(a.cases.read_text(encoding='utf-8'))
    data=''.join(json.dumps(c['input'],separators=(',',':'))+'\n' for c in cases).encode()
    actual=run('execute',[java,'-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-cp',str(jar)+os.pathsep+cp,'me.rerere.rikkahub.feature.math.RationalMathCliKt'],input=data)
    rows=[json.loads(line) for line in actual.splitlines() if line];assert len(rows)==len(cases)
    failures=[]
    for i,(case,row) in enumerate(zip(cases,rows)):
        result=row['result'];expected=case['expected'];status=result['status']
        if status!=expected['status']:failures.append(dict(index=i,expected=expected,actual=result));continue
        assert row['accepted']==(status=='VERIFIED')
        if status!='VERIFIED':assert not row['forged_status_accepted']
        if 'removed_domain_accepted' in row:assert not row['removed_domain_accepted']
        for key,value in expected.items():
            if result.get(key)!=value:failures.append(dict(index=i,key=key,expected=value,actual=result.get(key)))
        if status=='DISPROVED' and 'computed_left' in expected:
            witness=result['counterexample'];x=witness['x']
            def value(p):return sum((Fraction(v)*x**int(k) for k,v in p.items()),Fraction(0))
            for condition in expected['left_nonzero_polynomials']+expected['right_nonzero_polynomials']:assert value(condition)!=0
            def rational_value(v):return value(v['numerator'])/value(v['denominator'])
            l=rational_value(expected['computed_left']);r=rational_value(expected['claimed_right'])
            assert l!=r and Fraction(witness['left'])==l and Fraction(witness['right'])==r
    report=dict(status='PASS' if not failures else 'FAIL',cases=len(cases),failures=failures,sources={str(p):digest(p) for p in files},dependencies={p.name:digest(p) for p in deps},inputSha256=digest(a.cases),jarSha256=digest(jar),outputSha256=hashlib.sha256(actual).hexdigest(),runtime='pure Kotlin CLI; no Android/model execution')
    (folder/'RESULT.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(dict(status=report['status'],cases=report['cases'],failures=failures[:2]),ensure_ascii=False))
    if failures:raise SystemExit(1)
if __name__=='__main__':main()
