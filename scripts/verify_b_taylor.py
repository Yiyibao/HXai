"""Exercise real Kotlin Taylor verification and independently check formal derivatives plus exact remainders."""
from pathlib import Path
from fractions import Fraction as F
from math import comb
import argparse
import hashlib
import json
import subprocess
import sys

from math_certificate import O, derivative, evaluate
from verify_b_rational_integral import parse_expression

ROOT = Path(__file__).resolve().parents[1]
# A bounded 69760-bit exact result can exceed Python's default 4300 decimal-digit parsing guard.
sys.set_int_max_str_digits(30000)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def number(text):
    pieces = text.split('/')
    assert len(pieces) in (1, 2)
    return F(int(pieces[0]), int(pieces[1]) if len(pieces) == 2 else 1)


def check_taylor(args, result):
    assert result['contractVersion'] == 11 and result['domain'] == 'Q[x]'
    assert result['scope'] == 'finite_rational_center_polynomial_taylor_with_exact_remainder'
    if result['input_sha256'] is not None:
        encoded = json.dumps(args, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
        assert result['input_sha256'] == hashlib.sha256(encoded).hexdigest()
    if result['status'] == 'UNSUPPORTED':
        assert not result['verified'] and 'taylor_coefficients' not in result and 'remainder' not in result
        return False
    (polynomial, denominator), _ = parse_expression(args['expression'])
    assert denominator == [F(1)] and result['input_coefficients'] == O['coefficients'](polynomial)
    center, order = number(args['center']), int(args['order'])
    assert result['center'] == str(center) and result['order'] == order
    # Independent formal derivative/factorial evaluation, rather than the product's shifted Horner rows.
    derivatives = polynomial[:]
    factorial = 1
    shifted = []
    for k in range(len(polynomial)):
        shifted.append(evaluate(derivatives, center) / factorial)
        derivatives = derivative(derivatives)
        factorial *= k + 1
    assert result['shifted_coefficients'] == O['coefficients'](shifted)
    actual = [shifted[k] if k < len(shifted) else F(0) for k in range(order + 1)]
    expected = [number(v) for v in args['claimed_coefficients']]
    differences = [a - b for a, b in zip(actual, expected)]
    assert len(expected) == len(actual)
    assert result['taylor_coefficients'] == list(map(str, actual))
    assert result['claimed_coefficients'] == list(map(str, expected))
    assert result['coefficient_differences'] == list(map(str, differences))
    first = next((k for k, value in enumerate(differences) if value), None)
    assert result['first_mismatching_power'] == first
    assert result['status'] == ('VERIFIED' if first is None else 'DISPROVED') and result['verified'] == (first is None)
    quotient = shifted[order + 1:] or [F(0)]
    tail = result['remainder']
    assert tail['kind'] == 'exact_polynomial_factorization' and tail['factor_power'] == order + 1
    assert tail['quotient_coefficients'] == O['coefficients'](quotient)
    assert tail['identically_zero'] == (not any(quotient))
    assert result['truncation_equals_input_globally'] == (not any(quotient))
    assert result['coordinate'] == 'h=x-center' and result['identity_uses'] == 'computed_taylor_coefficients_not_unverified_claim'
    # Reconstruct p(x) from the actual truncated part plus h^(order+1)*Q(h), with h=x-center.
    full = actual[:]
    if len(full) < len(shifted):
        full.extend(quotient)
    reconstructed = [sum((full[k] * comb(k, i) * (-center) ** (k - i) for k in range(i, len(full))), F(0)) for i in range(len(full))]
    assert O['coefficients'](reconstructed) == O['coefficients'](polynomial)
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
        command = [sys.executable, str(ROOT / 'scripts/verify_b_rational_integral.py'), '--worktree', str(args.worktree.resolve()), '--cases', str(args.cases.resolve()), '--run-dir', str(args.run_dir / 'prior-and-runtime')]
        child = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=1500)
        (args.run_dir / 'runtime.log').write_bytes(child.stdout + b'\n' + child.stderr)
        (args.run_dir / 'COMMAND.json').write_text(json.dumps(dict(command=command, cwd=str(ROOT), exitCode=child.returncode), indent=2), encoding='utf-8')
        child.check_returncode()
        logs = list((args.run_dir / 'prior-and-runtime').rglob('execute.log'))
        assert len(logs) == 1
        rows = [json.loads(line) for line in logs[0].read_text(encoding='utf-8').splitlines() if line]
    selected = [row for row in rows if row['input'].get('operation') == 'polynomial_taylor']
    assert selected
    count = sum(check_taylor(row['input'], row['result']) for row in selected)
    report = dict(status='PASS', totalCases=len(rows), taylorCases=len(selected), certifiedTaylorResults=count, verifierSha256=digest(Path(__file__)), sourceMode='native persisted tool output' if args.native_dir else 'real Kotlin CLI and full prior corpus', algorithm='Independent Fraction formal derivatives/factorials at rational center, exact coefficient differences, and full truncated-plus-remainder binomial reconstruction in x; no sampling proof')
    (args.run_dir / 'RESULT.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
