"""Check actual Kotlin/Android rational closed-interval error bounds without trusting the result status."""
from pathlib import Path
from fractions import Fraction as F
import argparse
import hashlib
import json
import subprocess
import sys

from math_certificate import O, P, derivative, evaluate, variations, count_open, square_free, sturm, proportional_positive, divrem
from verify_b_rational_integral import parse_expression, rational_json
from verify_b_sturm_bound import check_sign
from verify_b_taylor import number

ROOT = Path(__file__).resolve().parents[1]


def check_certificate(args, result):
    assert result['contractVersion'] == 15 and result['domain'] == 'Q(x)'
    assert result['scope'] == 'finite_rational_interval_uniform_absolute_bound_on_both_original_rational_domains'
    if result['input_sha256'] is not None:
        encoded = json.dumps(args, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
        assert result['input_sha256'] == hashlib.sha256(encoded).hexdigest()
    if result['status'] == 'UNSUPPORTED':
        assert not result['verified']
        assert not any(key in result for key in ['upper_obligation', 'lower_obligation', 'interval_domain_checks', 'counterexample'])
        return False
    source, sc = parse_expression(args['expression'])
    approximate, ac = parse_expression(args['approximation'])
    sn, sd = source; an, ad = approximate
    n, d = O['reduce'](O['add'](O['mul'](sn, ad), O['mul'](an, sd), -1), O['mul'](sd, ad))
    assert result['source'] == rational_json(source)
    assert result['approximation'] == rational_json(approximate)
    assert result['error'] == rational_json((n, d))
    assert result['source_nonzero_polynomials'] == sc and result['approximation_nonzero_polynomials'] == ac
    a, b, claim = (number(args[key]) for key in ['lower', 'upper', 'claimed_bound'])
    assert a <= b and claim >= 0
    assert result['lower'] == str(a) and result['upper'] == str(b) and result['claimed_bound'] == str(claim)
    assert result['closed_interval'] == dict(lower=str(a), upper=str(b), every_point_defined=True)
    conditions = []
    for condition in sc + ac + [O['coefficients']([v/p[-1] for v in p]) for p in [sd, ad, d] if len(p) > 1]:
        if condition not in conditions:
            conditions.append(condition)
    checks = result['interval_domain_checks']
    assert [row['nonzero_polynomial'] for row in checks] == conditions
    for row in checks:
        polynomial = square_free(P(row['nonzero_polynomial']))
        assert row['square_free_polynomial'] == O['coefficients'](polynomial)
        sequence = [P(p) for p in row['sturm_sequence']]
        assert sequence[0] == polynomial
        if len(polynomial) > 1:
            proportional_positive(sequence[1], derivative(polynomial))
            for i in range(2, len(sequence)):
                proportional_positive(sequence[i], [-v for v in divrem(sequence[i-2], sequence[i-1])[1]])
            assert len(sequence[-1]) == 1 and not any(divrem(sequence[-2], sequence[-1])[1])
        else:
            assert len(sequence) == 1
        assert evaluate(polynomial, a) != 0 and evaluate(polynomial, b) != 0
        assert (0 if a == b else count_open(sturm(polynomial), a, b)) == row['open_interval_root_count'] == 0
        assert row['lower_variations'] == variations(sequence, a) and row['upper_variations'] == variations(sequence, b)
        assert not row['lower_is_root'] and not row['upper_is_root']
    upper_poly = O['mul'](O['add'](O['mul']([claim], d), n, -1), d)
    lower_poly = O['mul'](O['add'](O['mul']([claim], d), n), d)
    assert len(upper_poly) <= 33 and len(lower_poly) <= 33
    upper = check_sign(result['upper_obligation'], upper_poly, a, b)
    lower = check_sign(result['lower_obligation'], lower_poly, a, b)
    accepted = upper and lower
    assert result['status'] == result['claim_status'] == ('VERIFIED' if accepted else 'DISPROVED')
    assert result['verified'] == accepted
    assert result['proof_kind'] == 'exact_original_domain_and_denominator_squared_sturm_bound'
    assert result['polynomial_reduction'] == 'upper=(claim*D-N)*D;lower=(claim*D+N)*D;D_squared_positive_on_closed_interval'
    if not accepted:
        witness = result['counterexample']; x = F(witness['x'])
        assert a <= x <= b and all(evaluate(P(p), x) != 0 for p in conditions)
        source_value = evaluate(sn, x)/evaluate(sd, x)
        approximate_value = evaluate(an, x)/evaluate(ad, x)
        value = source_value - approximate_value
        assert value == evaluate(n, x)/evaluate(d, x) and abs(value) > claim
        assert witness['source_value'] == str(source_value) and witness['approximation_value'] == str(approximate_value)
        assert witness['error'] == str(value) and witness['absolute_error'] == str(abs(value))
    else:
        assert 'counterexample' not in result
    assert len(json.dumps(result, separators=(',', ':'), ensure_ascii=False)) <= 1048576
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worktree', type=Path)
    parser.add_argument('--cases', type=Path)
    parser.add_argument('--native-dir', type=Path)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=False)
    if args.native_dir is not None:
        rows = [json.loads(p.read_text(encoding='utf-8')) for p in sorted(args.native_dir.glob('*.json'))]
        assert rows
    else:
        assert args.worktree is not None and args.cases is not None
        command = [sys.executable, str(ROOT/'scripts/verify_b_sturm_bound.py'), '--worktree', str(args.worktree.resolve()), '--cases', str(args.cases.resolve()), '--run-dir', str(args.run_dir/'prior-and-runtime')]
        child = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=2400)
        (args.run_dir/'runtime.log').write_bytes(child.stdout+b'\n'+child.stderr)
        (args.run_dir/'COMMAND.json').write_text(json.dumps(dict(command=command, cwd=str(ROOT), exitCode=child.returncode), indent=2), encoding='utf-8')
        child.check_returncode()
        logs = list((args.run_dir/'prior-and-runtime').rglob('execute.log'))
        assert len(logs) == 1
        rows = [json.loads(line) for line in logs[0].read_text(encoding='utf-8').splitlines() if line]
    selected = [row for row in rows if row['input'].get('operation') == 'rational_uniform_bound_sturm']
    assert selected
    certificates = sum(check_certificate(row['input'], row['result']) for row in selected)
    report = dict(status='PASS', totalCases=len(rows), rationalBoundCases=len(selected), completeCertificates=certificates,
                  provedClaims=sum(row['result']['status']=='VERIFIED' for row in selected), disprovedClaims=sum(row['result']['status']=='DISPROVED' for row in selected),
                  verifierSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), sourceMode='native persisted tool output' if args.native_dir else 'real Kotlin CLI and full prior corpus',
                  algorithm='Independent closed AST Fraction original syntactic domains, exact rational difference, complete closed-interval domain Sturm checks, denominator-squared polynomial identity, both complete sign decompositions and original-domain exact violating witness; no sampling proof.')
    (args.run_dir/'RESULT.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
