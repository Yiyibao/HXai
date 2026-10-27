"""Run real Kotlin rational-limit operations and prior math; independently verify exact Fraction limit classifications."""
from pathlib import Path
from fractions import Fraction as F
import argparse, hashlib, json, subprocess, sys
from math_certificate import P, evaluate, derivative
ROOT=Path(__file__).resolve().parents[1]
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def finite(value):return dict(kind='finite',value=str(value))
def infinite(sign):return dict(kind='positive_infinity' if sign>0 else 'negative_infinity')
def check_limit(case,result):
    args=case['input'];value=case['expected']['canonical_rational_function'];n,d=P(value['numerator']),P(value['denominator'])
    if args['at'] in ['+inf','-inf']:
        ratio=n[-1]/d[-1];difference=len(n)-len(d)
        expected=finite(F(0)) if not any(n) or difference<0 else finite(ratio) if difference==0 else infinite(((ratio>0)-(ratio<0))*(-1 if args['at']=='-inf' and difference%2 else 1))
        assert result['limit']==expected
        assert result['proof']==dict(kind='exact_degree_and_leading_coefficients',numerator_degree=len(n)-1,denominator_degree=len(d)-1,numerator_leading_coefficient=str(n[-1]),denominator_leading_coefficient=str(d[-1]),leading_ratio=str(ratio),degree_difference=difference)
        assert 'point_value' not in result and 'point_defined' not in result and 'one_sided_limits' not in result
    else:
        parts=args['at'].split('/')
        target=F(int(parts[0]),int(parts[1]) if len(parts)==2 else 1);assert result['at']==str(target)
        numerator,denominator=evaluate(n,target),evaluate(d,target)
        defined=denominator!=0 and all(evaluate(P(p),target)!=0 for p in result['original_domain']['nonzero_polynomials'])
        assert result['point_defined']==defined and result['point_value']==(str(numerator/denominator) if defined else None)
        if denominator:
            left=right=finite(numerator/denominator)
            assert result['proof']==dict(kind='exact_rational_continuity_on_punctured_original_domain',numerator_at_point=str(numerator),denominator_at_point=str(denominator))
        else:
            assert numerator!=0
            p=d;order=0
            while evaluate(p,target)==0:p=derivative(p);order+=1
            first=evaluate(p,target);sign=((numerator>0)-(numerator<0))*((first>0)-(first<0))
            right=infinite(sign);left=infinite(sign if order%2==0 else -sign)
            assert result['proof']==dict(kind='exact_reduced_rational_pole_order',numerator_at_point=str(numerator),denominator_at_point='0',denominator_zero_order=order,first_nonzero_denominator_derivative=str(first))
        assert result['one_sided_limits']==dict(left=left,right=right)
        expected=left if args['side']=='left' else right if args['side']=='right' else left if left==right else dict(kind='does_not_exist',reason='unequal_one_sided_limits')
        assert result['limit']==expected

def main():
    p=argparse.ArgumentParser();p.add_argument('--worktree',type=Path,required=True);p.add_argument('--run-dir',type=Path,required=True);p.add_argument('--cases',type=Path,required=True);a=p.parse_args()
    out=a.run_dir.resolve();out.mkdir(parents=True,exist_ok=False)
    command=[sys.executable,str(ROOT/'scripts/verify_b_equivalence.py'),'--worktree',str(a.worktree.resolve()),'--run-dir',str(out/'prior-and-runtime'),'--cases',str(a.cases.resolve())]
    child=subprocess.run(command,cwd=ROOT,capture_output=True,timeout=800)
    (out/'runtime.log').write_bytes(child.stdout+b'\n'+child.stderr)
    (out/'COMMAND.json').write_text(json.dumps(dict(command=command,cwd=str(ROOT),exitCode=child.returncode),indent=2));child.check_returncode()
    cases=json.loads(a.cases.read_text(encoding='utf-8'))
    logfile=out/('prior-and-runtime/'*4+'runtime/execute.log')
    rows=[json.loads(line) for line in logfile.read_text(encoding='utf-8').splitlines() if line]
    assert len(cases)==len(rows);checked=0;limitcases=0
    for case,row in zip(cases,rows):
        if case['input'].get('operation')!='rational_limit':continue
        limitcases+=1;result=row['result']
        assert result['contractVersion']==7 and result['domain']=='Q(x)' and result['scope']=='rational_limit_along_original_real_domain'
        if result['input_sha256'] is not None:
            assert result['input_sha256']==hashlib.sha256(json.dumps(case['input'],sort_keys=True,separators=(',',':')).encode()).hexdigest()
        else:assert result['status']=='UNSUPPORTED'
        if result['status']=='UNSUPPORTED':assert 'limit' not in result;continue
        check_limit(case,result);checked+=1
    prior=json.loads((out/'prior-and-runtime/RESULT.json').read_text())
    report=dict(status='PASS',allCases=len(cases),limitCases=limitcases,certifiedLimits=checked,prior=prior,inputSha256=digest(a.cases),verifierSha256=digest(Path(__file__)),algorithm='exact Fraction canonical values/original domains; rational continuity, exact pole derivatives and degree/leading-coefficient limits, no sampling')
    (out/'RESULT.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
if __name__=='__main__':main()
