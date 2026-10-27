"""Run real Kotlin chain operations and all prior equation cases; independently check Fraction certificates."""
from pathlib import Path
from fractions import Fraction as F
import argparse, hashlib, json, subprocess, sys
from math_certificate import O, P, square_free, union, sturm, variations, evaluate, count_open, divrem, derivative, gcd, proportional_positive
ROOT=Path(__file__).resolve().parents[1]
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def check_domain(certificate, initial, expected_conditions):
    assert certificate['next_nonzero_polynomials']==expected_conditions
    excluded=[F(1)]
    for condition in expected_conditions: excluded=union(excluded,square_free(P(condition)))
    assert P(certificate['next_excluded_squarefree_coefficients'])==excluded
    extra=divrem(excluded,gcd(excluded[:],initial[:]))[0]
    assert P(certificate['new_exclusion_coefficients'])==extra
    sequence=[P(p) for p in certificate['sturm_sequence']]
    assert sequence[0]==extra
    if len(extra)==1: assert len(sequence)==1
    else:
        proportional_positive(sequence[1],derivative(extra))
        for i in range(2,len(sequence)):
            proportional_positive(sequence[i],[-v for v in divrem(sequence[i-2],sequence[i-1])[1]])
        assert len(sequence[-1])==1 and not any(divrem(sequence[-2],sequence[-1])[1])
    reference=sturm(extra); bound=F(certificate['root_bound'])
    assert bound>1+max((abs(v/extra[-1]) for v in extra[:-1]),default=F(0))
    expected=variations(reference,-bound)-variations(reference,bound)
    assert certificate['bound_variations']=={'negative':variations(sequence,-bound),'positive':variations(sequence,bound)}
    roots=certificate['new_excluded_roots'];assert expected==certificate['new_excluded_real_root_count']==len(roots)
    previous=None
    for index,root in enumerate(roots):
        assert root['index']==index
        isolation=root['isolation']
        if isolation['kind']=='rational_point':
            lower=upper=F(isolation['value']);assert evaluate(extra,lower)==0
        else:
            assert isolation['kind']=='open_rational_interval'
            lower,upper=F(isolation['lower']),F(isolation['upper'])
            assert lower<upper and evaluate(extra,lower)!=0 and evaluate(extra,upper)!=0
            assert count_open(reference,lower,upper)==isolation['root_count']==1
        if previous is not None:
            assert previous<=lower
            if previous==lower: assert evaluate(extra,lower)!=0
        previous=upper
    assert certificate['status']==('VERIFIED' if expected==0 else 'DISPROVED')
    return expected

def check_chain(case,row):
    result=row['result'];args=case['input']
    assert result['contractVersion']==5 and result['domain']=='Q(x)'
    assert result['scope']=='rational_expression_chain_on_initial_defined_real_domain'
    if result['input_sha256'] is not None:
        assert result['input_sha256']==hashlib.sha256(json.dumps(args,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    else:
        assert result['status']=='UNSUPPORTED'
    if 'oracle_domains' not in case:return 0
    initial=[F(1)]
    for condition in case['oracle_domains'][0]:initial=union(initial,square_free(P(condition)))
    assert P(result['initial_excluded_squarefree_coefficients'])==initial
    assert result['initial_domain']==dict(ambient='real_numbers',predicate='all_polynomials_nonzero',nonzero_polynomials=case['oracle_domains'][0])
    certified=0
    for index,check in enumerate(result['checks']):
        if check['status']=='UNSUPPORTED':continue
        count=check_domain(check['domain_check'],initial,case['oracle_domains'][index+1]);certified+=1
        if count:
            assert check['status']=='DISPROVED' and check['reason']=='expression_undefined_on_initial_domain' and 'value_check' not in check
            continue
        value=check['value_check'];left,right=case['oracle_values'][index:index+2]
        assert value['computed_left']==left and value['claimed_right']==right
        difference=O['add'](O['mul'](P(left['numerator']),P(right['denominator'])),O['mul'](P(right['numerator']),P(left['denominator'])),-1)
        assert P(value['cross_difference_coefficients'])==difference
        assert value['status']==check['status']==('VERIFIED' if not any(difference) else 'DISPROVED')
        if any(difference):
            witness=value['counterexample'];x=F(witness['x'])
            assert all(evaluate(P(p),x)!=0 for p in value['comparison_domain']['nonzero_polynomials'])
            l=evaluate(P(left['numerator']),x)/evaluate(P(left['denominator']),x)
            r=evaluate(P(right['numerator']),x)/evaluate(P(right['denominator']),x)
            assert l!=r and F(witness['left'])==l and F(witness['right'])==r
    if result['status']=='VERIFIED':
        assert result['canonical_final_expression']==case['oracle_values'][-1]
        assert len(result['checks'])==result['verified_prefix_transitions']==len(args['expressions'])-1
    else: assert len(result['checks'])==result['verified_prefix_transitions']+1
    return certified

def main():
    p=argparse.ArgumentParser();p.add_argument('--worktree',type=Path,required=True);p.add_argument('--run-dir',type=Path,required=True);p.add_argument('--cases',type=Path,required=True);a=p.parse_args()
    out=a.run_dir.resolve();out.mkdir(parents=True,exist_ok=False)
    command=[sys.executable,str(ROOT/'scripts/verify_b_equation.py'),'--worktree',str(a.worktree.resolve()),'--run-dir',str(out/'prior-and-runtime'),'--cases',str(a.cases.resolve())]
    child=subprocess.run(command,cwd=ROOT,capture_output=True,timeout=600)
    (out/'runtime.log').write_bytes(child.stdout+b'\n'+child.stderr)
    (out/'COMMAND.json').write_text(json.dumps(dict(command=command,cwd=str(ROOT),exitCode=child.returncode),indent=2));child.check_returncode()
    cases=json.loads(a.cases.read_text(encoding='utf-8'))
    rows=[json.loads(line) for line in (out/'prior-and-runtime/prior-and-runtime/runtime/execute.log').read_text(encoding='utf-8').splitlines() if line]
    assert len(cases)==len(rows);certified=0;chains=0
    for case,row in zip(cases,rows):
        if case['input'].get('operation')!='rational_expression_chain':continue
        certified+=check_chain(case,row);chains+=1
    prior=json.loads((out/'prior-and-runtime/RESULT.json').read_text())
    report=dict(status='PASS',allCases=len(cases),chainCases=chains,certifiedChainTransitions=certified,certifiedEquations=prior['certifiedEquations'],certifiedInequalities=prior['certifiedInequalities'],inputSha256=digest(a.cases),verifierSha256=digest(Path(__file__)),priorReportSha256=digest(out/'prior-and-runtime/RESULT.json'),algorithm='independent exact Fraction values, fixed original domains, square-free gcd and complete Sturm root certificates')
    (out/'RESULT.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
if __name__=='__main__':main()
