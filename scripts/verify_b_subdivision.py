"""Independently verify complete exact polynomial enclosure partitions from true Kotlin/Android output."""
from pathlib import Path
from fractions import Fraction as F
from math import comb
import argparse
import hashlib
import json
import subprocess
import sys

from math_certificate import O
from verify_b_uniform_bound import exact_enclosure
from verify_b_taylor import number

ROOT = Path(__file__).resolve().parents[1]
sys.set_int_max_str_digits(50000)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_basis(args, actual):
    source, approximate, error, power, expected = exact_enclosure(args)
    values = [number(value) for value in actual]
    assert values == expected
    n = len(error) - 1
    reconstructed = [sum((values[k] * comb(n, k) * comb(n - k, i - k) * (-1) ** (i - k) for k in range(i + 1)), F(0)) for i in range(n + 1)]
    assert reconstructed == power  # Entire polynomial identity, not point samples or de Casteljau replay.
    return source, approximate, error, power, values


def check_certificate(args, result):
    assert result['contractVersion'] == 13 and result['domain'] == 'Q[x]'
    assert result['scope'] == 'finite_rational_interval_polynomial_uniform_absolute_bound_by_subdivided_bernstein'
    if result['input_sha256'] is not None:
        encoded = json.dumps(args, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
        assert result['input_sha256'] == hashlib.sha256(encoded).hexdigest()
    if 'leaves' not in result:
        assert result['status'] == 'UNSUPPORTED' and not result['verified']
        return False
    source, approximate, error, power, root_coefficients = check_basis(args, result['initial_bernstein_coefficients'])
    assert result['source_coefficients'] == O['coefficients'](source)
    assert result['approximation_coefficients'] == O['coefficients'](approximate)
    assert result['error_coefficients'] == O['coefficients'](error)
    assert result['initial_unit_interval_power_coefficients'] == O['coefficients'](power)
    assert result['bernstein_degree'] == len(error) - 1
    root_low, root_high = min(root_coefficients), max(root_coefficients)
    assert result['initial_error_enclosure'] == dict(lower=str(root_low), upper=str(root_high))
    assert result['initial_certified_absolute_bound'] == str(max(abs(root_low), abs(root_high)))
    a, b, claim = (number(args[key]) for key in ['lower', 'upper', 'claimed_bound'])
    assert a <= b and claim >= 0
    assert result['lower'] == str(a) and result['upper'] == str(b) and result['interval_width'] == str(b-a)
    assert result['claimed_bound'] == str(claim)
    depth_limit = int(args['max_depth'])
    assert args['max_depth'] in list(map(str, range(9))) and result['max_depth'] == depth_limit
    leaves = result['leaves']
    assert 1 <= len(leaves) <= 2 ** depth_limit
    assert result['leaf_count'] == len(leaves) and result['split_count'] == len(leaves)-1
    cursor = a
    all_coefficients = []
    depth_limited = False
    for leaf in leaves:
        lo, hi = number(leaf['lower']), number(leaf['upper'])
        depth = leaf['depth']
        assert cursor == lo <= hi and 0 <= depth <= depth_limit
        if a == b:
            assert len(leaves) == 1 and depth == 0 and lo == hi == a
        else:
            assert hi-lo == (b-a)/2**depth
            assert ((lo-a)/(b-a)*2**depth).denominator == 1
        _, _, _, _, coefficients = check_basis(dict(args, lower=str(lo), upper=str(hi)), leaf['bernstein_coefficients'])
        low, high = min(coefficients), max(coefficients)
        bound = max(abs(low), abs(high))
        assert leaf['error_enclosure'] == dict(lower=str(low), upper=str(high))
        assert leaf['certified_absolute_bound'] == str(bound)
        stop = 'bound_certified' if bound <= claim else 'point_interval' if lo == hi else 'constant_error' if len(error) == 1 else 'depth_limit'
        assert leaf['stop_reason'] == stop
        if stop == 'depth_limit':
            assert depth == depth_limit
            depth_limited = True
        cursor = hi
        all_coefficients.extend(coefficients)
    assert cursor == b  # No gaps, lost right branches, or omitted failed leaves.
    low, high = min(all_coefficients), max(all_coefficients)
    bound = max(abs(low), abs(high))
    assert result['error_enclosure'] == dict(lower=str(low), upper=str(high))
    assert result['certified_absolute_bound'] == str(bound)
    assert result['deepest_leaf'] == max(leaf['depth'] for leaf in leaves)
    assert result['depth_limit_reached'] == depth_limited
    accepted = claim >= bound
    assert result['status'] == ('VERIFIED' if accepted else 'UNSUPPORTED') and result['verified'] == accepted
    assert result['claim_status'] == ('VERIFIED' if accepted else 'NOT_CERTIFIED')
    if not accepted:
        assert result['reason'] == 'claimed_bound_not_certified_within_subdivision_limits'
    assert result['bound_kind'] == 'sufficient_not_necessarily_sharp'
    assert result['proof_kind'] == 'exact_midpoint_de_casteljau_bernstein_partition_convex_hulls'
    assert result['coverage'] == 'all_terminal_closed_intervals_including_uncertified_leaves'
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
        command = [sys.executable, str(ROOT/'scripts/verify_b_uniform_bound.py'), '--worktree', str(args.worktree.resolve()), '--cases', str(args.cases.resolve()), '--run-dir', str(args.run_dir/'prior-and-runtime')]
        child = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=2000)
        (args.run_dir/'runtime.log').write_bytes(child.stdout+b'\n'+child.stderr)
        (args.run_dir/'COMMAND.json').write_text(json.dumps(dict(command=command,cwd=str(ROOT),exitCode=child.returncode),indent=2),encoding='utf-8')
        child.check_returncode()
        logs = list((args.run_dir/'prior-and-runtime').rglob('execute.log'))
        assert len(logs) == 1
        rows = [json.loads(line) for line in logs[0].read_text(encoding='utf-8').splitlines() if line]
    selected = [row for row in rows if row['input'].get('operation') == 'polynomial_uniform_bound_subdivision']
    assert selected
    certificates = sum(check_certificate(row['input'], row['result']) for row in selected)
    report = dict(status='PASS',totalCases=len(rows),subdivisionCases=len(selected),completePartitionCertificates=certificates,provedClaims=sum(row['result']['status']=='VERIFIED' for row in selected),verifierSha256=digest(Path(__file__)),sourceMode='native persisted tool output' if args.native_dir else 'real Kotlin CLI and full prior corpus',algorithm='Independent Fraction direct affine binomial substitution on every closed leaf and full Bernstein-basis identity, dyadic depth and exact gap-free coverage, maximum of every leaf hull; no de Casteljau replay or sampling proof')
    (args.run_dir/'RESULT.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
