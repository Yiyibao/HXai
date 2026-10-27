"""Independent exact Fraction checks shared by rational-equation/inequality CLI evidence."""
from pathlib import Path
from fractions import Fraction as F
import hashlib, json, runpy
ROOT=Path(__file__).resolve().parents[1]
ORACLE=ROOT/'HXai_CHANGE_REVIEW/B-RATIONAL-INEQUALITY-20261015/golden/oracle.py'
O=runpy.run_path(str(ORACLE))
P=O['polynomial'];evaluate=O['evaluate'];sturm=O['sturm'];variations=O['variations'];count_open=O['count_open'];divrem=O['divrem'];derivative=O['derivative'];gcd=O['gcd']
def selected(value,relation):return value==0 if relation=='==' else O['selected'](value,relation)
def square_free(p):
    if len(p)==1:return [F(1)]
    q=divrem(p,gcd(p[:],derivative(p)))[0]
    return [v/q[-1] for v in q]
def union(a,b):
    p=O['mul'](divrem(a,gcd(a[:],b[:]))[0],b)
    return [v/p[-1] for v in p]
def proportional_positive(actual,expected):
    assert any(actual) and any(expected) and len(actual)==len(expected)
    scale=actual[-1]/expected[-1];assert scale>0 and actual==[v*scale for v in expected]
def check_decomposition(result,relation,oracle_roots=None,oracle_excluded=None):
    polynomial=P(result['critical_squarefree_coefficients']);assert any(polynomial)
    assert len(gcd(polynomial[:],derivative(polynomial)))==1
    sequence=[P(p) for p in result['sturm_sequence']];assert sequence[0]==polynomial
    if len(polynomial)==1:assert len(sequence)==1
    else:
        assert len(sequence)>=2;proportional_positive(sequence[1],derivative(polynomial))
        for i in range(2,len(sequence)):
            expected=[-v for v in divrem(sequence[i-2],sequence[i-1])[1]]
            proportional_positive(sequence[i],expected)
        assert not any(divrem(sequence[-2],sequence[-1])[1]) and len(sequence[-1])==1
    reference=sturm(polynomial)
    bound=F(result['root_bound']);assert bound>1+max((abs(v/polynomial[-1]) for v in polynomial[:-1]),default=F(0))
    count=variations(reference,-bound)-variations(reference,bound)
    assert count==result['real_root_count']==len(result['roots'])
    assert result['bound_variations']=={'negative':variations(sequence,-bound),'positive':variations(sequence,bound)}
    spans=[]
    for i,root in enumerate(result['roots']):
        assert root['index']==i
        isolation=root['isolation']
        if isolation['kind']=='rational_point':
            lower=upper=F(isolation['value']);assert evaluate(polynomial,lower)==0
        else:
            assert isolation['kind']=='open_rational_interval';lower=F(isolation['lower']);upper=F(isolation['upper'])
            assert lower<upper and evaluate(polynomial,lower)!=0 and evaluate(polynomial,upper)!=0
            assert count_open(reference,lower,upper)==isolation['root_count']==1
        if spans:
            assert spans[-1][1]<=lower
            if spans[-1][1]==lower:assert evaluate(polynomial,lower)!=0
        if oracle_roots is not None:
            exact=F(oracle_roots[i]);assert lower==exact if lower==upper else lower<exact<upper
        if oracle_excluded is not None:assert root['excluded']==oracle_excluded[i]
        spans.append((lower,upper))
    domain=[P(p) for p in result['original_domain']['nonzero_polynomials']]
    excluded=P(result['excluded_squarefree_coefficients']);assert not any(divrem(polynomial,excluded)[1])
    excluded_sequence=sturm(excluded)
    points=[]
    for root,(lo,hi) in zip(result['roots'],spans):
        forbidden=evaluate(excluded,lo)==0 if lo==hi else count_open(excluded_sequence,lo,hi)==1
        assert forbidden==root['excluded']
        chosen=not forbidden and selected(F(0),relation)
        assert chosen==root['selected'];points.append(chosen)
    n=P(result['canonical_rational_function']['numerator']);d=P(result['canonical_rational_function']['denominator'])
    expected_excluded=[F(1)]
    for condition in domain:expected_excluded=union(expected_excluded,square_free(condition))
    assert excluded==expected_excluded
    assert polynomial==union(excluded,square_free(n))
    cells=[];assert len(result['cells'])==count+1
    for i,cell in enumerate(result['cells']):
        assert cell['index']==i
        assert cell['lower']==O['endpoint'](None if i==0 else i-1)
        assert cell['upper']==O['endpoint'](None if i==count else i,True)
        sample=F(cell['sample']);assert evaluate(polynomial,sample)!=0
        if spans:
            if i==0:assert sample<=spans[0][0]
            elif i==count:assert sample>=spans[-1][1]
            else:assert spans[i-1][1]<=sample<=spans[i][0]
        assert all(evaluate(p,sample)!=0 for p in domain)
        value=evaluate(n,sample)/evaluate(d,sample)
        assert F(cell['value'])==value and cell['sign']==(value>0)-(value<0)
        chosen=selected(value,relation);assert cell['selected']==chosen;cells.append(chosen)
    assert result['solution_intervals']==O['merged_solution'](cells,points)
def check_certificate(case,result):
    equation=case['input']['operation']=='rational_equation'
    relation='==' if equation else case['input']['relation']
    check_decomposition(result,relation,case.get('oracle_roots'),case.get('oracle_excluded'))
    n=P(result['canonical_rational_function']['numerator']);d=P(result['canonical_rational_function']['denominator'])
    if equation:
        sides=result['canonical_sides'];left=sides['left'];right=sides['right']
        ln,ld=P(left['numerator']),P(left['denominator']);rn,rd=P(right['numerator']),P(right['denominator'])
        difference=O['add'](O['mul'](ln,rd),O['mul'](rn,ld),-1)
        assert (n,d)==O['reduce'](difference,O['mul'](ld,rd))
        assert result['original_nonzero_polynomials']==result['left_nonzero_polynomials']+result['right_nonzero_polynomials']
    expected_hash=hashlib.sha256(json.dumps(case['input'],sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
    assert result['input_sha256']==expected_hash
    assert result['scope']==('rational_equation_on_common_defined_real_domain' if equation else 'rational_inequality_on_original_defined_real_domain')
    assert result['contractVersion']==(4 if equation else 3)
    assert result['relation']==relation and result['domain']=='Q(x)'

