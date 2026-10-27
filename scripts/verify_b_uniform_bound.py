"""Check true Kotlin/Android uniform-error certificates by independent exact affine and basis identities."""
from pathlib import Path
from fractions import Fraction as F
from math import comb
import argparse
import hashlib
import json
import subprocess
import sys

from math_certificate import O
from verify_b_rational_integral import parse_expression
from verify_b_taylor import number

ROOT = Path(__file__).resolve().parents[1]
sys.set_int_max_str_digits(50000)  # Bounded 139392-bit product results, not unlimited integer parsing.


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def exact_enclosure(args):
    (source, sd), _ = parse_expression(args['expression'])
    (approximate, ad), _ = parse_expression(args['approximation'])
    assert sd == ad == [F(1)]
    error = O['add'](source, approximate, -1)
    a, b = number(args['lower']), number(args['upper'])
    assert a <= b
    width = b - a
    n = len(error) - 1
    power = [sum((error[j] * comb(j, i) * a ** (j - i) * width ** i for j in range(i, n + 1)), F(0)) for i in range(n + 1)]
    bernstein = [sum((power[i] * F(comb(k, i), comb(n, i)) for i in range(k + 1)), F(0)) for k in range(n + 1)]
    return source, approximate, error, power, bernstein


def check_certificate(args, result):
    assert result['contractVersion'] == 12 and result['domain'] == 'Q[x]'
    assert result['scope'] == 'finite_rational_interval_polynomial_uniform_absolute_bound_by_bernstein'
    if result['input_sha256'] is not None:
        encoded = json.dumps(args, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
        assert result['input_sha256'] == hashlib.sha256(encoded).hexdigest()
    if 'bernstein_coefficients' not in result:
        assert result['status'] == 'UNSUPPORTED' and not result['verified']
        return False
    source, approximate, error, power, bernstein = exact_enclosure(args)
    assert result['source_coefficients'] == O['coefficients'](source)
    assert result['approximation_coefficients'] == O['coefficients'](approximate)
    assert result['error_coefficients'] == O['coefficients'](error)
    assert result['unit_interval_power_coefficients'] == O['coefficients'](power)
    assert result['bernstein_coefficients'] == list(map(str, bernstein))
    n = len(error) - 1
    assert result['bernstein_degree'] == n and len(bernstein) == n + 1
    # Expand the entire Bernstein basis back to unit-interval powers; no finite-point proof.
    reconstructed = [sum((bernstein[k] * comb(n, k) * comb(n - k, i - k) * (-1) ** (i - k) for k in range(i + 1)), F(0)) for i in range(n + 1)]
    assert reconstructed == power
    a, b, claim = (number(args[k]) for k in ['lower', 'upper', 'claimed_bound'])
    assert claim >= 0 and result['lower'] == str(a) and result['upper'] == str(b)
    assert result['interval_width'] == str(b - a) and result['claimed_bound'] == str(claim)
    low, high = min(bernstein), max(bernstein)
    bound = max(abs(low), abs(high))
    assert result['error_enclosure'] == dict(lower=str(low), upper=str(high))
    assert result['certified_absolute_bound'] == str(bound)
    accepted = claim >= bound
    assert result['status'] == ('VERIFIED' if accepted else 'UNSUPPORTED') and result['verified'] == accepted
    assert result['claim_status'] == ('VERIFIED' if accepted else 'NOT_CERTIFIED')
    assert result['bound_kind'] == 'sufficient_not_necessarily_sharp'
    if not accepted:
        assert result['reason'] == 'claimed_bound_not_certified_by_bernstein_hull'
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
        command = [sys.executable, str(ROOT / 'scripts/verify_b_taylor.py'), '--worktree', str(args.worktree.resolve()), '--cases', str(args.cases.resolve()), '--run-dir', str(args.run_dir / 'prior-and-runtime')]
        child = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=1800)
        (args.run_dir / 'runtime.log').write_bytes(child.stdout + b'\n' + child.stderr)
        (args.run_dir / 'COMMAND.json').write_text(json.dumps(dict(command=command, cwd=str(ROOT), exitCode=child.returncode), indent=2), encoding='utf-8')
        child.check_returncode()
        logs = list((args.run_dir / 'prior-and-runtime').rglob('execute.log'))
        assert len(logs) == 1
        rows = [json.loads(line) for line in logs[0].read_text(encoding='utf-8').splitlines() if line]
    selected = [row for row in rows if row['input'].get('operation') == 'polynomial_uniform_bound']
    assert selected
    certificates = sum(check_certificate(row['input'], row['result']) for row in selected)
    proved = sum(row['result']['status'] == 'VERIFIED' for row in selected)
    report = dict(status='PASS', totalCases=len(rows), uniformBoundCases=len(selected), enclosureCertificates=certificates, provedClaims=proved, verifierSha256=digest(Path(__file__)), sourceMode='native persisted tool output' if args.native_dir else 'real Kotlin CLI and full prior corpus', algorithm='Independent Fraction affine binomial substitution, Bernstein coefficient formula and full basis-to-power identity; exact convex-hull absolute bound, no sampling and no false disproof of tighter claims')
    (args.run_dir / 'RESULT.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
