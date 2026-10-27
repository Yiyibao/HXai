"""Exercise real Kotlin equation-equivalence and prior operations; independently check exact real-root differences."""
from pathlib import Path
from fractions import Fraction as F
import argparse, hashlib, json, subprocess, sys
from math_certificate import P, sturm, variations, evaluate, count_open, divrem, derivative, proportional_positive
ROOT=Path(__file__).resolve().parents[1]
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def check_roots(proof, expected):
    polynomial=P(expected);assert proof['polynomial']==expected
    sequence=[P(p) for p in proof['sturm_sequence']];assert sequence[0]==polynomial
    if len(polynomial)==1: assert len(sequence)==1
    else:
        proportional_positive(sequence[1],derivative(polynomial))
        for i in range(2,len(sequence)):
            proportional_positive(sequence[i],[-v for v in divrem(sequence[i-2],sequence[i-1])[1]])
        assert len(sequence[-1])==1 and not any(divrem(sequence[-2],sequence[-1])[1])
    reference=sturm(polynomial);bound=F(proof['root_bound'])
    assert bound>1+max((abs(v/polynomial[-1]) for v in polynomial[:-1]),default=F(0))
    count=variations(reference,-bound)-variations(reference,bound)
    assert proof['bound_variations']=={'negative':variations(sequence,-bound),'positive':variations(sequence,bound)}
    assert count==proof['real_root_count']==len(proof['roots'])
    previous=None
    for index,root in enumerate(proof['roots']):
        assert root['index']==index
        isolation=root['isolation']
        if isolation['kind']=='rational_point':
            lower=upper=F(isolation['value']);assert evaluate(polynomial,lower)==0
        else:
            assert isolation['kind']=='open_rational_interval';lower,upper=F(isolation['lower']),F(isolation['upper'])
            assert lower<upper and evaluate(polynomial,lower)!=0 and evaluate(polynomial,upper)!=0
            assert count_open(reference,lower,upper)==isolation['root_count']==1
        if previous is not None:
            assert previous<=lower
            if previous==lower:assert evaluate(polynomial,lower)!=0
        previous=upper
    return count

def main():
    p=argparse.ArgumentParser();p.add_argument('--worktree',type=Path,required=True);p.add_argument('--run-dir',type=Path,required=True);p.add_argument('--cases',type=Path,required=True);a=p.parse_args()
    out=a.run_dir.resolve();out.mkdir(parents=True,exist_ok=False)
    command=[sys.executable,str(ROOT/'scripts/verify_b_chain.py'),'--worktree',str(a.worktree.resolve()),'--run-dir',str(out/'prior-and-runtime'),'--cases',str(a.cases.resolve())]
    child=subprocess.run(command,cwd=ROOT,capture_output=True,timeout=700)
    (out/'runtime.log').write_bytes(child.stdout+b'\n'+child.stderr)
    (out/'COMMAND.json').write_text(json.dumps(dict(command=command,cwd=str(ROOT),exitCode=child.returncode),indent=2));child.check_returncode()
    cases=json.loads(a.cases.read_text(encoding='utf-8'))
    rows=[json.loads(line) for line in (out/'prior-and-runtime/prior-and-runtime/prior-and-runtime/runtime/execute.log').read_text(encoding='utf-8').splitlines() if line]
    assert len(cases)==len(rows);certified=0;equivalences=0
    for case,row in zip(cases,rows):
        if case['input'].get('operation')!='rational_equation_equivalence':continue
        equivalences+=1;result=row['result']
        assert result['contractVersion']==6 and result['domain']=='Q(x)'
        assert result['scope']=='rational_equation_equivalence_over_original_real_solution_sets'
        if result['input_sha256'] is not None:
            assert result['input_sha256']==hashlib.sha256(json.dumps(case['input'],sort_keys=True,separators=(',',':')).encode()).hexdigest()
        else:assert result['status']=='UNSUPPORTED'
        if result['status']=='UNSUPPORTED':
            assert 'before' not in result and 'after' not in result
            continue
        if result['proof_kind']=='finite_vs_cofinite_real_solution_sets':
            assert result['before']['solution_kind']!=result['after']['solution_kind']
            assert result['status']=='DISPROVED' and not result['equivalent']
            assert 'added_solutions' not in result and 'removed_solutions' not in result
        else:
            assert result['proof_kind']=='exact_real_solution_set_symmetric_difference'
            removed=check_roots(result['removed_solutions'],case['oracle_removed_polynomial'])
            added=check_roots(result['added_solutions'],case['oracle_added_polynomial'])
            assert result['equivalent']==(removed==0 and added==0)
            assert result['status']==('VERIFIED' if result['equivalent'] else 'DISPROVED');certified+=2
    prior=json.loads((out/'prior-and-runtime/RESULT.json').read_text())
    report=dict(status='PASS',allCases=len(cases),equivalenceCases=equivalences,certifiedSolutionDifferences=certified,prior=prior,inputSha256=digest(a.cases),verifierSha256=digest(Path(__file__)),algorithm='independent Fraction canonical sides/original domains/finite or cofinite real root sets; exact symmetric-difference Sturm certificates')
    (out/'RESULT.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
if __name__=='__main__':main()
