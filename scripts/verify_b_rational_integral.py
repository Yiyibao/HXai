"""Independently certify actual rational-primitive integral results, including original syntactic domains."""
from pathlib import Path
from fractions import Fraction as F
import argparse
import ast
import hashlib
import json
import subprocess
import sys

from math_certificate import O, P, derivative, evaluate, variations, count_open, square_free, sturm, proportional_positive, divrem

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_expression(text):
    """Closed AST arithmetic over Fraction polynomial pairs; no eval or executable names."""
    assert len(text) <= 4096
    tree = ast.parse(text.replace('^', '**'), mode='eval')
    assert sum(1 for _ in ast.walk(tree)) <= 1024
    conditions = []

    def nonzero(n):
        assert any(n)
        if len(n) > 1:
            monic = O['coefficients']([v / n[-1] for v in n])
            if monic not in conditions:
                conditions.append(monic)

    def walk(node):
        if isinstance(node, ast.Constant):
            assert type(node.value) is int
            return [F(node.value)], [F(1)]
        if isinstance(node, ast.Name):
            assert node.id == 'x'
            return [F(0), F(1)], [F(1)]
        if isinstance(node, ast.UnaryOp):
            n, d = walk(node.operand)
            assert isinstance(node.op, (ast.UAdd, ast.USub))
            return ([-v for v in n] if isinstance(node.op, ast.USub) else n), d
        assert isinstance(node, ast.BinOp)
        n, d = walk(node.left)
        if isinstance(node.op, ast.Pow):
            assert isinstance(node.right, ast.Constant) and type(node.right.value) is int
            exponent = node.right.value
            assert 0 <= exponent <= 32
            if exponent == 0:
                nonzero(n)
            pn, pd = [F(1)], [F(1)]
            for _ in range(exponent):
                pn, pd = O['mul'](pn, n), O['mul'](pd, d)
            return O['reduce'](pn, pd)
        rn, rd = walk(node.right)
        if isinstance(node.op, ast.Add):
            return O['reduce'](O['add'](O['mul'](n, rd), O['mul'](rn, d)), O['mul'](d, rd))
        if isinstance(node.op, ast.Sub):
            return O['reduce'](O['add'](O['mul'](n, rd), O['mul'](rn, d), -1), O['mul'](d, rd))
        if isinstance(node.op, ast.Mult):
            return O['reduce'](O['mul'](n, rn), O['mul'](d, rd))
        assert isinstance(node.op, ast.Div)
        nonzero(rn)
        return O['reduce'](O['mul'](n, rd), O['mul'](d, rn))

    return walk(tree.body), conditions


def rational_json(value):
    n, d = value
    return dict(numerator=O['coefficients'](n), denominator=O['coefficients'](d))


def check_certificate(args, result):
    assert result['contractVersion'] == 10 and result['domain'] == 'Q(x)'
    assert result['scope'] == 'finite_rational_endpoint_integral_with_rational_primitive_certificate'
    if result['input_sha256'] is not None:
        encoded = json.dumps(args, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()
        assert result['input_sha256'] == hashlib.sha256(encoded).hexdigest()
    if result['status'] == 'UNSUPPORTED':
        assert not result['verified'] and 'exact_value' not in result
        return False
    integrand, ic = parse_expression(args['expression'])
    primitive, pc = parse_expression(args['primitive'])
    assert result['integrand'] == rational_json(integrand)
    assert result['primitive'] == rational_json(primitive)
    assert result['integrand_nonzero_polynomials'] == ic and result['primitive_nonzero_polynomials'] == pc
    n, d = primitive
    dn, dd = O['reduce'](O['add'](O['mul'](derivative(n), d), O['mul'](n, derivative(d)), -1), O['mul'](d, d))
    assert result['primitive_derivative'] == rational_json((dn, dd))
    cross = O['add'](O['mul'](dn, integrand[1]), O['mul'](integrand[0], dd), -1)
    assert result['derivative_cross_difference'] == O['coefficients'](cross)
    a, b, claim = (F(args[k]) for k in ['lower', 'upper', 'claimed_value'])
    lo, hi = min(a, b), max(a, b)
    assert result['closed_interval'] == dict(lower=str(lo), upper=str(hi), every_point_defined=True)
    conditions = ic[:]
    for condition in pc + [O['coefficients']([v / p[-1] for v in p]) for p in [integrand[1], primitive[1]] if len(p) > 1]:
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
                proportional_positive(sequence[i], [-v for v in divrem(sequence[i - 2], sequence[i - 1])[1]])
            assert not any(divrem(sequence[-2], sequence[-1])[1]) and len(sequence[-1]) == 1
        else:
            assert len(sequence) == 1
        assert evaluate(polynomial, lo) != 0 and evaluate(polynomial, hi) != 0
        reference = sturm(polynomial)
        assert (0 if lo == hi else count_open(reference, lo, hi)) == row['open_interval_root_count'] == 0
        assert row['lower_variations'] == variations(sequence, lo) and row['upper_variations'] == variations(sequence, hi)
        assert not row['lower_is_root'] and not row['upper_is_root']
    if any(cross):
        assert result['status'] == result['certificate_status'] == 'DISPROVED'
        assert result['claim_status'] == 'NOT_EVALUATED' and not result['verified'] and 'exact_value' not in result
    else:
        lower = evaluate(n, a) / evaluate(d, a)
        upper = evaluate(n, b) / evaluate(d, b)
        value = upper - lower
        assert result['endpoint_values'] == dict(lower=str(lower), upper=str(upper))
        assert result['exact_value'] == str(value) and result['claim_difference'] == str(value - claim)
        assert result['certificate_status'] == 'VERIFIED'
        assert result['status'] == result['claim_status'] == ('VERIFIED' if value == claim else 'DISPROVED')
        assert result['verified'] == (value == claim)
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worktree', type=Path)
    parser.add_argument('--cases', type=Path)
    parser.add_argument('--native-dir', type=Path)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=False)
    rows = []
    if args.native_dir is not None:
        rows = [json.loads(p.read_text(encoding='utf-8')) for p in sorted(args.native_dir.glob('*.json'))]
        assert rows
    else:
        assert args.worktree is not None and args.cases is not None
        command = [sys.executable, str(ROOT / 'scripts/verify_b_integral.py'), '--worktree', str(args.worktree.resolve()), '--cases', str(args.cases.resolve()), '--run-dir', str(args.run_dir / 'prior-and-runtime')]
        child = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=1200)
        (args.run_dir / 'runtime.log').write_bytes(child.stdout + b'\n' + child.stderr)
        (args.run_dir / 'COMMAND.json').write_text(json.dumps(dict(command=command, cwd=str(ROOT), exitCode=child.returncode), indent=2), encoding='utf-8')
        child.check_returncode()
        logs = list((args.run_dir / 'prior-and-runtime').rglob('execute.log'))
        assert len(logs) == 1
        rows = [json.loads(line) for line in logs[0].read_text(encoding='utf-8').splitlines() if line]
    selected = [row for row in rows if row['input'].get('operation') == 'rational_primitive_integral']
    assert selected
    count = sum(check_certificate(row['input'], row['result']) for row in selected)
    report = dict(status='PASS', totalCases=len(rows), rationalIntegralCases=len(selected), certifiedResults=count, verifierSha256=digest(Path(__file__)), sourceMode='native persisted tool output' if args.native_dir else 'real Kotlin CLI and full prior corpus', algorithm='Independent closed AST Fraction rational arithmetic, formal quotient derivative, original syntactic domains, Sturm remainder/variation/root counts and exact endpoint differences')
    (args.run_dir / 'RESULT.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
