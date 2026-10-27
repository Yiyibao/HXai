"""Exercise real Kotlin equations plus prior math regressions; independently verify exact certificates."""
from pathlib import Path
import argparse, hashlib, json, subprocess, sys
from math_certificate import ORACLE, check_certificate
ROOT=Path(__file__).resolve().parents[1]
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--worktree',type=Path,required=True);parser.add_argument('--run-dir',type=Path,required=True);parser.add_argument('--cases',type=Path,required=True);args=parser.parse_args()
    out=args.run_dir.resolve();out.mkdir(parents=True,exist_ok=False)
    command=[sys.executable,str(ROOT/'scripts/verify_b_inequality.py'),'--worktree',str(args.worktree.resolve()),'--run-dir',str(out/'prior-and-runtime'),'--cases',str(args.cases.resolve())]
    child=subprocess.run(command,cwd=ROOT,capture_output=True,timeout=420)
    (out/'runtime.log').write_bytes(child.stdout+b'\n'+child.stderr)
    (out/'COMMAND.json').write_text(json.dumps(dict(command=command,cwd=str(ROOT),exitCode=child.returncode),indent=2));child.check_returncode()
    cases=json.loads(args.cases.read_text(encoding='utf-8'));rows=[json.loads(line) for line in (out/'prior-and-runtime/runtime/execute.log').read_text(encoding='utf-8').splitlines() if line]
    assert len(cases)==len(rows);checked=0
    for case,row in zip(cases,rows):
        if case['input'].get('operation')!='rational_equation':continue
        if row['result']['status']=='UNSUPPORTED':assert 'solution_intervals' not in row['result'];continue
        check_certificate(case,row['result']);checked+=1
    prior=json.loads((out/'prior-and-runtime/RESULT.json').read_text())
    report=dict(status='PASS',allCases=len(cases),certifiedEquations=checked,certifiedInequalities=prior['certifiedInequalities'],inputSha256=digest(args.cases),oracleSha256=digest(ORACLE),verifierSha256=digest(Path(__file__)),certificateVerifierSha256=digest(Path(__file__).with_name('math_certificate.py')),priorReportSha256=digest(out/'prior-and-runtime/RESULT.json'),runtimeReportSha256=digest(out/'prior-and-runtime/runtime/RESULT.json'),algorithm='exact Fraction canonical difference, original domains, complete Sturm roots and interval union')
    (out/'RESULT.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
if __name__=='__main__':main()
