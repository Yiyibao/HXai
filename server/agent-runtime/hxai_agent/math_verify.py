"""Bounded exact Q[x] identities/derivatives and equal-expression chain checking.

NOT a general theorem prover, OCR engine, equation-equivalence test or proof grader.
No eval/exec, external parser, subprocess or network. Unsupported input is explicit.
"""
from __future__ import annotations
import ast
import hashlib
import json
import re
from fractions import Fraction

MAX_CHARS, MAX_NODES, MAX_DEGREE, MAX_BITS = 4096, 256, 32, 1024
Polynomial = dict[int, Fraction]

class Unsupported(ValueError): pass


def bounded(poly: Polynomial) -> Polynomial:
    result = {power: value for power, value in poly.items() if value}
    if any(not 0 <= power <= MAX_DEGREE for power in result): raise Unsupported('degree_limit')
    if any(max(abs(value.numerator).bit_length(), value.denominator.bit_length()) > MAX_BITS for value in result.values()):
        raise Unsupported('coefficient_limit')
    return result


def add(left: Polynomial, right: Polynomial, sign=1) -> Polynomial:
    result = left.copy()
    for power, value in right.items(): result[power] = result.get(power, Fraction(0)) + sign * value
    return bounded(result)


def multiply(left: Polynomial, right: Polynomial) -> Polynomial:
    result: Polynomial = {}
    for lp, lv in left.items():
        for rp, rv in right.items():
            if lp + rp > MAX_DEGREE: raise Unsupported('degree_limit')
            result[lp + rp] = result.get(lp + rp, Fraction(0)) + lv * rv
    return bounded(result)


def parse_polynomial(expression: str) -> Polynomial:
    if not isinstance(expression, str) or not 0 < len(expression) <= MAX_CHARS: raise Unsupported('expression_length')
    if not re.fullmatch(r'[0-9x\s+*/()^\-]+', expression): raise Unsupported('only_rational_polynomials_in_x')
    try:
        tree = ast.parse(expression.replace('^', '**').strip(), mode='eval')
    except (SyntaxError, ValueError, RecursionError) as error:
        raise Unsupported('invalid_expression') from error
    if sum(1 for _ in ast.walk(tree)) > MAX_NODES: raise Unsupported('node_limit')
    def visit(node):
        if isinstance(node, ast.Expression): return visit(node.body)
        if isinstance(node, ast.Constant) and type(node.value) is int: return bounded({0: Fraction(node.value)})
        if isinstance(node, ast.Name) and node.id == 'x': return {1: Fraction(1)}
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
            value = visit(node.operand)
            return value if isinstance(node.op, ast.UAdd) else {p: -v for p, v in value.items()}
        if isinstance(node, ast.BinOp):
            left = visit(node.left)
            if isinstance(node.op, ast.Pow):
                if not isinstance(node.right, ast.Constant) or type(node.right.value) is not int:
                    raise Unsupported('literal_nonnegative_exponent_required')
                exponent = node.right.value
                if not 0 <= exponent <= MAX_DEGREE: raise Unsupported('exponent_limit')
                result = {0: Fraction(1)}
                for _ in range(exponent): result = multiply(result, left)
                return result
            right = visit(node.right)
            if isinstance(node.op, ast.Add): return add(left, right)
            if isinstance(node.op, ast.Sub): return add(left, right, -1)
            if isinstance(node.op, ast.Mult): return multiply(left, right)
            if isinstance(node.op, ast.Div):
                if set(right) != {0} or not right[0]: raise Unsupported('nonzero_constant_denominator_required')
                return bounded({p: v / right[0] for p, v in left.items()})
        raise Unsupported('unsupported_expression_node')
    try:
        return visit(tree)
    except (RecursionError, OverflowError, ZeroDivisionError) as error:
        raise Unsupported('invalid_or_excessive_expression') from error


def value(poly: Polynomial, x: int) -> Fraction:
    return sum((coefficient * Fraction(x) ** power for power, coefficient in poly.items()), Fraction(0))


def compare(left, right):
    difference = add(left, right, -1)
    if not difference:
        return {'status': 'VERIFIED', 'verified': True, 'proof_kind': 'exact_rational_coefficients'}
    # A nonzero polynomial of degree <= 32 cannot vanish at all 33 distinct points.
    # We already proved it is nonzero by exact coefficients; sampling is only a witness.
    x = next(n for n in range(MAX_DEGREE + 1) if value(difference, n) != 0)
    return {'status': 'DISPROVED', 'verified': False, 'proof_kind': 'exact_counterexample',
            'counterexample': {'x': x, 'left': str(value(left, x)), 'right': str(value(right, x))},
            'difference_coefficients': {str(p): str(v) for p, v in sorted(difference.items())}}


def verify_identity(left: str, right: str) -> dict:
    try:
        result = compare(parse_polynomial(left), parse_polynomial(right))
    except Unsupported as error:
        result = {'status': 'UNSUPPORTED', 'verified': False, 'reason': str(error)}
    result['scope'] = 'formal_univariate_rational_polynomial_identity'
    result['input_sha256'] = hashlib.sha256(json.dumps([left, right], ensure_ascii=False).encode()).hexdigest()
    return result


def verify_derivative(expression: str, claimed_derivative: str) -> dict:
    try:
        poly = parse_polynomial(expression)
        derivative = bounded({p - 1: p * v for p, v in poly.items() if p})
        result = compare(derivative, parse_polynomial(claimed_derivative))
    except Unsupported as error:
        result = {'status': 'UNSUPPORTED', 'verified': False, 'reason': str(error)}
    result['scope'] = 'formal_polynomial_derivative'
    return result


def first_error(expressions: list[str]) -> dict:
    """A chain of equal EXPRESSIONS, not equivalent equations or arbitrary proofs."""
    if not isinstance(expressions, list) or not 2 <= len(expressions) <= 50 or not all(isinstance(s, str) for s in expressions):
        raise ValueError('provide_2_to_50_expression_strings')
    checks = []
    for step in range(1, len(expressions)):
        result = verify_identity(expressions[step - 1], expressions[step])
        checks.append(result)
        if result['status'] != 'VERIFIED':
            return {'status': result['status'], 'scope': 'equal_expression_chain_only',
                    'first_error_transition': step if result['status'] == 'DISPROVED' else None,
                    'unverified_transition': step if result['status'] == 'UNSUPPORTED' else None,
                    'verified_prefix_transitions': step - 1, 'checks': checks}
    return {'status': 'VERIFIED', 'scope': 'equal_expression_chain_only',
            'first_error_transition': None, 'verified_prefix_transitions': len(checks), 'checks': checks}
