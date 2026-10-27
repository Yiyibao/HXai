import importlib.util
from pathlib import Path
import pytest

spec = importlib.util.spec_from_file_location('domain_compatibility', Path(__file__).parents[1] / 'verify_b_domain_compatibility.py')
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)


def packet(code, cls='sample.SourceTest', method='realSource', reordered=False):
    fields = [('class', cls), ('test', method)]
    if reordered:
        fields.reverse()
    return '\n'.join('INSTRUMENTATION_STATUS: ' + key + '=' + value for key, value in fields) + '\nINSTRUMENTATION_STATUS_CODE: ' + str(code) + '\n'


@pytest.mark.parametrize('reordered', [False, True])
def test_discovery_binds_fields_within_packets_not_adjoining_tests(reordered):
    text = ''.join(packet(1, method=name, reordered=reordered) + packet(0, method=name, reordered=not reordered)
                   for name in ['keepsOriginal', 'rejectsForgedQuote']) + 'OK (2 tests)'
    assert compat.instrumentation_methods(text) == [
        'sample.SourceTest#keepsOriginal', 'sample.SourceTest#rejectsForgedQuote']


@pytest.mark.parametrize('text', [
    'OK (0 tests)',
    packet(1) + 'OK (2 tests)',
    packet(1) + packet(1) + 'OK (2 tests)',
    'INSTRUMENTATION_STATUS: test=onlyName\nINSTRUMENTATION_STATUS_CODE: 1\nOK (1 test)',
])
def test_missing_duplicate_or_unaccounted_discovery_never_grants_execution(text):
    with pytest.raises(ValueError):
        compat.instrumentation_methods(text)


def test_success_requires_requested_method_final_status_not_adb_exit_or_discovery():
    target = 'sample.SourceTest#realSource'
    assert compat.method_outcome(packet(1, reordered=True) + packet(0) + 'OK (1 test)', target) == 'PASS'
    assert compat.method_outcome(packet(1, reordered=True) + 'OK (1 test)', target) == 'FAIL'
    assert compat.method_outcome(packet(0, method='otherMethod') + 'OK (1 test)', target) == 'FAIL'
    assert compat.method_outcome(packet(-2) + 'FAILURES!!!\nTests run: 1, Failures: 1', target) == 'FAIL'
    assert compat.method_outcome(packet(0) + 'OK (1 test)\nINSTRUMENTATION_FAILED', target) == 'FAIL'


@pytest.mark.parametrize('code', [-3, -4])
def test_ignored_or_assumption_boundary_is_skip_not_pass(code):
    assert compat.method_outcome(packet(code, reordered=True) + 'OK (1 test)', 'sample.SourceTest#realSource') == 'SKIP'
