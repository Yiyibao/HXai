"""Independently check true Kotlin/Android closed-interval Sturm error-bound certificates."""
from pathlib import Path
from fractions import Fraction as F
import argparse
import hashlib
import json
import subprocess
import sys

from math_certificate import O, P, derivative, evaluate, variations, count_open, square_free, sturm, proportional_positive, divrem
from verify_b_rational_integral import parse_expression
from verify_b_taylor import number

ROOT = Path(__file__).resolve().parents[1]


def check_sign(proof, polynomial, a, b):
    assert proof['coefficients'] == O['coefficients'](polynomial)
    av, bv = evaluate(polynomial, a), evaluate(polynomial, b)
    assert proof['endpoint_values'] == dict(lower=str(av), upper=str(bv))
    signs = [av >= 0, bv >= 0]
    if a == b:
        assert proof['proof_kind'] == 'exact_point_evaluation'
    else:
        assert proof['proof_kind'] == 'complete_closed_interval_sturm_sign_decomposition'
        critical = square_free(polynomial)
        assert proof['square_free_coefficients'] == O['coefficients'](critical)
        sequence = [P(p) for p in proof['sturm_sequence']]
        assert sequence[0] == critical
        if len(critical) == 1:
            assert len(sequence) == 1
        else:
            proportional_positive(sequence[1], derivative(critical))
            for i in range(2, len(sequence)):
                proportional_positive(sequence[i], [-v for v in divrem(sequence[i-2], sequence[i-1])[1]])
            assert len(sequence[-1]) == 1 and not any(divrem(sequence[-2], sequence[-1])[1])
        reference = sturm(critical)
        at_a, at_b = evaluate(critical, a) == 0, evaluate(critical, b) == 0
        expected_open = count_open(reference, a, b)
        assert proof['lower_variations'] == variations(sequence, a)
        assert proof['upper_variations'] == variations(sequence, b)
        assert proof['lower_is_root'] == at_a and proof['upper_is_root'] == at_b
        assert proof['open_interval_root_count'] == expected_open
        roots = proof['roots']
        assert len(roots) == proof['closed_interval_root_count'] == expected_open + at_a + at_b
        spans = []
        for i, root in enumerate(roots):
            assert root['index'] == i
            isolation = root['isolation']
            if isolation['kind'] == 'rational_point':
                lo = hi = F(isolation['value'])
                assert evaluate(critical, lo) == 0
            else:
                assert isolation['kind'] == 'open_rational_interval'
                lo, hi = F(isolation['lower']), F(isolation['upper'])
                assert lo < hi and evaluate(critical, lo) != 0 and evaluate(critical, hi) != 0
                assert count_open(reference, lo, hi) == isolation['root_count'] == 1
            assert a <= lo <= hi <= b
            if spans:
                assert spans[-1][1] <= lo
                if spans[-1][1] == lo:
                    assert evaluate(critical, lo) != 0
            spans.append((lo, hi))
        # Each isolated root is distinct; matching the closed root count proves none was omitted.
        indices = [i for i in range(len(roots)+1)
                   if not (i == 0 and spans and spans[0] == (a, a))
                   and not (i == len(roots) and spans and spans[-1] == (b, b))]
        assert [cell['index'] for cell in proof['cells']] == indices

        def endpoint(i):
            return dict(kind='lower_endpoint') if i < 0 else dict(kind='upper_endpoint') if i == len(roots) else dict(kind='root', index=i)

        for cell in proof['cells']:
            i = cell['index']
            assert cell['lower'] == endpoint(i-1) and cell['upper'] == endpoint(i)
            x = F(cell['sample'])
            assert a <= x <= b
            if spans:
                if i > 0:
                    assert spans[i-1][1] <= x
                if i < len(spans):
                    assert x <= spans[i][0]
            assert evaluate(critical, x) != 0
            value = evaluate(polynomial, x)
            assert cell['value'] == str(value) and cell['sign'] == (value > 0) - (value < 0)
            signs.append(value >= 0)
        assert 1 <= proof['isolation_work_nodes'] <= 8192
    assert proof['nonnegative'] == all(signs)
    return all(signs)


def check_certificate(args, result):
    assert result['contractVersion'] == 14 and result['domain'] == 'Q[x]'
    assert result['scope'] == 'finite_rational_interval_polynomial_uniform_absolute_bound_by_sturm'
    if result['input_sha256'] is not None:
        encoded = json.dumps(args, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
        assert result['input_sha256'] == hashlib.sha256(encoded).hexdigest()
    if result['status'] == 'UNSUPPORTED':
        assert not result['verified'] and 'upper_obligation' not in result and 'lower_obligation' not in result
        assert 'counterexample' not in result
        return False
    (source, sd), _ = parse_expression(args['expression'])
    (approximate, ad), _ = parse_expression(args['approximation'])
    assert sd == ad == [F(1)]
    error = O['add'](source, approximate, -1)
    assert result['source_coefficients'] == O['coefficients'](source)
    assert result['approximation_coefficients'] == O['coefficients'](approximate)
    assert result['error_coefficients'] == O['coefficients'](error)
    a, b, claim = (number(args[key]) for key in ['lower', 'upper', 'claimed_bound'])
    assert a <= b and claim >= 0
    assert result['lower'] == str(a) and result['upper'] == str(b) and result['claimed_bound'] == str(claim)
    upper = check_sign(result['upper_obligation'], O['add']([claim], error, -1), a, b)
    lower = check_sign(result['lower_obligation'], O['add']([claim], error), a, b)
    accepted = upper and lower
    assert result['status'] == result['claim_status'] == ('VERIFIED' if accepted else 'DISPROVED')
    assert result['verified'] == accepted
    assert result['proof_kind'] == 'exact_two_sided_closed_interval_sturm_nonnegativity'
    if not accepted:
        witness = result['counterexample']; x = F(witness['x']); value = evaluate(error, x)
        assert a <= x <= b and abs(value) > claim
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
        command = [sys.executable, str(ROOT/'scripts/verify_b_subdivision.py'), '--worktree', str(args.worktree.resolve()), '--cases', str(args.cases.resolve()), '--run-dir', str(args.run_dir/'prior-and-runtime')]
        child = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=2200)
        (args.run_dir/'runtime.log').write_bytes(child.stdout+b'\n'+child.stderr)
        (args.run_dir/'COMMAND.json').write_text(json.dumps(dict(command=command, cwd=str(ROOT), exitCode=child.returncode), indent=2), encoding='utf-8')
        child.check_returncode()
        logs = list((args.run_dir/'prior-and-runtime').rglob('execute.log'))
        assert len(logs) == 1
        rows = [json.loads(line) for line in logs[0].read_text(encoding='utf-8').splitlines() if line]
    selected = [row for row in rows if row['input'].get('operation') == 'polynomial_uniform_bound_sturm']
    assert selected
    certificates = sum(check_certificate(row['input'], row['result']) for row in selected)
    report = dict(status='PASS', totalCases=len(rows), sturmBoundCases=len(selected), completeCertificates=certificates,
                  provedClaims=sum(row['result']['status']=='VERIFIED' for row in selected), disprovedClaims=sum(row['result']['status']=='DISPROVED' for row in selected),
                  verifierSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), sourceMode='native persisted tool output' if args.native_dir else 'real Kotlin CLI and full prior corpus',
                  algorithm='Independent Fraction AST, whole error polynomial, square-free gcd, positive-scaled Sturm remainder chain, exact closed root counts, complete distinct isolations and sign-cell coverage; exact two-sided nonnegativity and violating rational witness. No sampling proof.')
    (args.run_dir/'RESULT.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
