"""Execute real Kotlin integral verification plus all prior math, independently checking Fraction certificates."""
from pathlib import Path
from fractions import Fraction
import argparse
import hashlib
import json
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def coefficients(values):
    return {str(i): str(value) for i, value in enumerate(values) if value}


def check_integral(case, result):
    assert result['contractVersion'] == 9 and result['domain'] == 'Q[x]'
    assert result['scope'] == 'finite_rational_endpoint_polynomial_definite_integral'
    if result['input_sha256'] is not None:
        assert result['input_sha256'] == hashlib.sha256(json.dumps(case['input'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    if result['status'] == 'UNSUPPORTED':
        assert not result['verified'] and 'exact_value' not in result
        return False
    args = case['input']
    original = [Fraction(v) for v in case['oracle_coefficients']]
    primitive = [Fraction(0)] + [value / (i + 1) for i, value in enumerate(original)]
    assert result['integrand_coefficients'] == coefficients(original)
    assert result['antiderivative_coefficients'] == coefficients(primitive)
    # Formal differentiation certifies this primitive, not a set of sampled points.
    assert [value * i for i, value in enumerate(primitive) if i] == original
    a, b, claim = (Fraction(args[key]) for key in ['lower', 'upper', 'claimed_value'])
    def at(x):
        return sum((value * x ** i for i, value in enumerate(primitive)), Fraction(0))
    lower, upper = at(a), at(b)
    actual = upper - lower
    assert result['endpoint_values'] == dict(lower=str(lower), upper=str(upper))
    assert result['lower'] == str(a) and result['upper'] == str(b) and result['claimed_value'] == str(claim)
    assert result['exact_value'] == str(actual) and result['claim_difference'] == str(actual - claim)
    assert result['status'] == ('VERIFIED' if actual == claim else 'DISPROVED')
    assert result['verified'] == (actual == claim)
    assert result['proof_kind'] == 'exact_polynomial_primitive_and_newton_leibniz'
    assert result['integration_domain'] == 'oriented_finite_interval_in_real_numbers'
    assert result['continuity_basis'] == 'polynomial_continuous_on_all_real_numbers'
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--worktree', type=Path, required=True)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--cases', type=Path, required=True)
    args = parser.parse_args()
    out = args.run_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    command = [sys.executable, str(ROOT / 'scripts/verify_b_monotonicity.py'), '--worktree', str(args.worktree.resolve()),
               '--run-dir', str(out / 'prior-and-runtime'), '--cases', str(args.cases.resolve())]
    child = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=1000)
    (out / 'runtime.log').write_bytes(child.stdout + b'\n' + child.stderr)
    (out / 'COMMAND.json').write_text(json.dumps(dict(command=command, cwd=str(ROOT), exitCode=child.returncode), indent=2))
    child.check_returncode()
    cases = json.loads(args.cases.read_text(encoding='utf-8'))
    rows = [json.loads(line) for line in (out / ('prior-and-runtime/' * 6 + 'runtime/execute.log')).read_text(encoding='utf-8').splitlines() if line]
    assert len(cases) == len(rows)
    checked = 0
    total = 0
    for case, row in zip(cases, rows):
        if case['input'].get('operation') != 'polynomial_definite_integral':
            continue
        total += 1
        checked += check_integral(case, row['result'])
    report = dict(status='PASS', allCases=len(cases), integralCases=total, certifiedIntegrals=checked,
                  inputSha256=digest(args.cases), verifierSha256=digest(Path(__file__)),
                  prior=json.loads((out / 'prior-and-runtime/RESULT.json').read_text()),
                  algorithm='Independent Fraction coefficients, formal primitive derivative, exact rational endpoint powers and difference; no quadrature or sampling proof')
    (out / 'RESULT.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
