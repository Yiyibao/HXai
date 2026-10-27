"""Exercise true Kotlin monotonicity and all prior math; verify derivative/Sturm evidence and maximal connected branches."""
from pathlib import Path
from fractions import Fraction as F
import argparse, hashlib, json, subprocess, sys
from math_certificate import O, P, derivative, check_decomposition
ROOT=Path(__file__).resolve().parents[1]
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def check_monotonicity(case,result):
    value=case['expected']['canonical_rational_function'];n,d=P(value['numerator']),P(value['denominator'])
    dn,dd=O['reduce'](O['add'](O['mul'](derivative(n),d),O['mul'](n,derivative(d)),-1),O['mul'](d,d))
    evidence=result['derivative_evidence']
    assert evidence['canonical_rational_function']==dict(numerator=O['coefficients'](dn),denominator=O['coefficients'](dd))
    assert evidence['relation']=='==' and evidence['original_nonzero_polynomials']==case['oracle_conditions']
    domain=case['oracle_conditions'][:]
    if len(dd)>1:
        denominator=O['coefficients']([v/dd[-1] for v in dd])
        if denominator not in domain:domain.append(denominator)
    assert evidence['original_domain']==dict(ambient='real_numbers',predicate='all_polynomials_nonzero',nonzero_polynomials=domain)
    check_decomposition(evidence,'==',case.get('oracle_roots'),case.get('oracle_excluded'))
    signs=[cell['sign'] for cell in evidence['cells']];excluded=[root['excluded'] for root in evidence['roots']]
    constant=not any(dn);assert result['stationary_everywhere']==constant
    expected_points=[]
    if constant:
        assert result['increasing_intervals']==result['decreasing_intervals']==[]
        assert result['constant_intervals']==O['merged_solution']([True]*len(signs),[not v for v in excluded])
    else:
        assert all(sign in [-1,1] for sign in signs)
        inc_cells=[s>0 for s in signs];dec_cells=[s<0 for s in signs]
        inc_points=[not bad and (signs[i]>0 or signs[i+1]>0) for i,bad in enumerate(excluded)]
        dec_points=[not bad and (signs[i]<0 or signs[i+1]<0) for i,bad in enumerate(excluded)]
        assert result['increasing_intervals']==O['merged_solution'](inc_cells,inc_points)
        assert result['decreasing_intervals']==O['merged_solution'](dec_cells,dec_points)
        assert result['constant_intervals']==[]
        for i,bad in enumerate(excluded):
            if bad:continue
            classification='local_maximum' if signs[i]>0>signs[i+1] else 'local_minimum' if signs[i]<0<signs[i+1] else 'stationary_non_extremum'
            expected_points.append(dict(root_index=i,classification=classification))
    assert result['stationary_points']==expected_points
    assert result['proof_kind']=='exact_derivative_sturm_monotonic_branches'
    assert result['branch_scope']=='maximal_connected_non_degenerate_intervals_in_original_real_domain'

def main():
    p=argparse.ArgumentParser();p.add_argument('--worktree',type=Path,required=True);p.add_argument('--run-dir',type=Path,required=True);p.add_argument('--cases',type=Path,required=True);a=p.parse_args()
    out=a.run_dir.resolve();out.mkdir(parents=True,exist_ok=False)
    command=[sys.executable,str(ROOT/'scripts/verify_b_limit.py'),'--worktree',str(a.worktree.resolve()),'--run-dir',str(out/'prior-and-runtime'),'--cases',str(a.cases.resolve())]
    child=subprocess.run(command,cwd=ROOT,capture_output=True,timeout=900)
    (out/'runtime.log').write_bytes(child.stdout+b'\n'+child.stderr)
    (out/'COMMAND.json').write_text(json.dumps(dict(command=command,cwd=str(ROOT),exitCode=child.returncode),indent=2));child.check_returncode()
    cases=json.loads(a.cases.read_text(encoding='utf-8'));logfile=out/('prior-and-runtime/'*5+'runtime/execute.log')
    rows=[json.loads(line) for line in logfile.read_text(encoding='utf-8').splitlines() if line]
    assert len(cases)==len(rows);checked=0;monotonicitycases=0
    for case,row in zip(cases,rows):
        if case['input'].get('operation')!='rational_monotonicity':continue
        monotonicitycases+=1;result=row['result']
        assert result['contractVersion']==8 and result['domain']=='Q(x)' and result['scope']=='rational_monotonic_branches_on_original_real_domain'
        if result['input_sha256'] is not None:
            assert result['input_sha256']==hashlib.sha256(json.dumps(case['input'],sort_keys=True,separators=(',',':')).encode()).hexdigest()
        else:assert result['status']=='UNSUPPORTED'
        if result['status']=='UNSUPPORTED':assert 'increasing_intervals' not in result;continue
        check_monotonicity(case,result);checked+=1
    prior=json.loads((out/'prior-and-runtime/RESULT.json').read_text())
    report=dict(status='PASS',allCases=len(cases),monotonicityCases=monotonicitycases,certifiedMonotonicity=checked,prior=prior,inputSha256=digest(a.cases),verifierSha256=digest(Path(__file__)),certificateHelperSha256=digest(Path(__file__).with_name('math_certificate.py')),algorithm='independent Fraction quotient derivative; full Sturm decomposition and maximal connected branches merging legal stationary points, preserving holes')
    (out/'RESULT.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
if __name__=='__main__':main()
