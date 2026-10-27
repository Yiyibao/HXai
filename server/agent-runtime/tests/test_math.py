import random
from fractions import Fraction
import pytest
from hxai_agent.math_verify import first_error, parse_polynomial, verify_derivative, verify_identity

@pytest.mark.parametrize('left,right', [
 ('(x+1)^2','x*x+2*x+1'), ('(x-1)*(x+1)','x^2-1'),
 ('(2*x+1)/3','2*x/3+1/3'), ('x-x','0'), ('x^0','1'),
 ('(x+1)^3','1+3*x+3*x*x+x^3'), ('x/2+x/2','x')])
def test_exact_identities(left,right):
    assert verify_identity(left,right)['status']=='VERIFIED'

@pytest.mark.parametrize('expression,derivative', [('x^3-3*x','3*x^2-3'),('x^2/2','x'),('9','0'),('(x+1)^2','2*x+2')])
def test_derivatives(expression,derivative):
    assert verify_derivative(expression,derivative)['status']=='VERIFIED'

@pytest.mark.parametrize('expression', ['1/x','sqrt(x)','sin(x)','__import__(1)','x**100000000','x**-1',
 'x**x','0.1*x','x//2','1/0','x=1','x[0]','x;1','(x**32)*x','1'*4000,'x+'*300+'1'])
def test_unsupported_never_becomes_proved(expression):
    assert verify_identity(expression,'0')['status']=='UNSUPPORTED'

def test_first_error_and_correct_nonstandard_method():
    result=first_error(['(x+1)^2','x^2+2*x+1','x^2+1','x^2'])
    assert result['first_error_transition']==2 and result['verified_prefix_transitions']==1
    assert result['checks'][-1]['counterexample']=={'x':1,'left':'4','right':'2'}
    assert first_error(['(x+1)^2','1+x+x+x*x','1+2*x+x^2'])['status']=='VERIFIED'

def test_unknown_prefix_blocks_first_error_claim():
    result=first_error(['x','1/x','0'])
    assert result['first_error_transition'] is None
    assert result['unverified_transition']==1 and result['status']=='UNSUPPORTED'

def test_sampling_is_not_a_proof():
    result=verify_identity('x*(x-1)*(x-2)*(x-3)','0')
    assert result['status']=='DISPROVED' and result['counterexample']['x']==4

def test_domain_sensitive_cancellation_is_unsupported():
    assert verify_identity('(x^2-1)/(x-1)','x+1')['status']=='UNSUPPORTED'

def test_independent_dense_coefficient_crosscheck():
    rng=random.Random(20261012)
    for _ in range(120):
        a=[Fraction(rng.randint(-5,5),rng.randint(1,5)) for _ in range(5)]
        b=[Fraction(rng.randint(-5,5),rng.randint(1,5)) for _ in range(4)]
        def expression(coeffs):
            return '+'.join(f'({v.numerator}/{v.denominator})*x^{i}' for i,v in enumerate(coeffs))
        dense=[sum((a[i]*b[k-i] for i in range(len(a)) if 0<=k-i<len(b)),Fraction(0)) for k in range(len(a)+len(b)-1)]
        assert parse_polynomial('('+expression(a)+')*('+expression(b)+')')=={i:v for i,v in enumerate(dense) if v}
        derivative=[i*a[i] for i in range(1,len(a))]
        assert verify_derivative(expression(a),expression(derivative))['status']=='VERIFIED'
