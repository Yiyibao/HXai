"""Run the real Kotlin verifier and independently check complete Sturm/sign/solution evidence.
Uses only installed compiler jars and Python Fraction; no installs/network/models.
"""
from pathlib import Path
import argparse, hashlib, json, subprocess, sys
ROOT=Path(__file__).resolve().parents[1]
from math_certificate import ORACLE, check_certificate
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()

def main():
    p=argparse.ArgumentParser();p.add_argument('--worktree',type=Path,required=True);p.add_argument('--run-dir',type=Path,required=True);p.add_argument('--cases',type=Path,required=True);a=p.parse_args()
    out=a.run_dir.resolve();out.mkdir(parents=True,exist_ok=False)
    command=[sys.executable,str(ROOT/'scripts/verify_b_rational_math.py'),'--worktree',str(a.worktree.resolve()),'--run-dir',str(out/'runtime'),'--cases',str(a.cases.resolve())]
    child=subprocess.run(command,cwd=ROOT,capture_output=True,timeout=360)
    (out/'runtime.log').write_bytes(child.stdout+b'\n'+child.stderr)
    (out/'COMMAND.json').write_text(json.dumps(dict(command=command,cwd=str(ROOT),exitCode=child.returncode),indent=2));child.check_returncode()
    cases=json.loads(a.cases.read_text());actual=[json.loads(line) for line in (out/'runtime/execute.log').read_text(encoding='utf-8').splitlines() if line]
    assert len(cases)==len(actual);checked=0
    for case,row in zip(cases,actual):
        if case['input'].get('operation')!='rational_inequality':continue
        if row['result']['status']=='UNSUPPORTED':assert 'solution_intervals' not in row['result'];continue
        check_certificate(case,row['result']);checked+=1
    report=dict(status='PASS',allCases=len(cases),certifiedInequalities=checked,inputSha256=digest(a.cases),oracleSha256=digest(ORACLE),verifierSha256=digest(Path(__file__)),certificateVerifierSha256=digest(Path(__file__).with_name('math_certificate.py')),runtimeReportSha256=digest(out/'runtime/RESULT.json'),algorithm='independent exact Fraction Sturm recurrence, complete root coverage, signs/domain and interval union')
    (out/'RESULT.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
if __name__=='__main__':main()
