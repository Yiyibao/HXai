import collections
import json
import re
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

QA = Path(__file__).parent
ROOT = QA.parents[1]

def results(names):
    records, totals = [], collections.Counter()
    for name in names:
        raw = (QA / name).read_text(encoding='utf-8-sig', errors='replace')
        counts = collections.Counter(re.findall(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', raw))
        result = dict(log=name, passed=counts['0'], failed=counts['-2'], errors=counts['-1'], skipped=counts['-3'] + counts['-4'])
        records.append(result)
        totals.update({k: result[k] for k in ('passed', 'failed', 'errors', 'skipped')})
    return dict(totals=totals, records=records)

phase = json.loads((QA / 'final-results.json').read_text(encoding='utf-8-sig'))
summary = {'regressionVersion': results([r['log'] for r in phase]),
           'candidateVersion': results([n + '.log' for n in ('seed-gate-fixed', 'verify-gate-fixed', 'diagnostics-gate-fixed', 'skills-install-gate-fixed', 'skills-use-gate-fixed-retry', 'boundary-gate-fixed', 'word-negative-gate-fixed')]),
           'firstVersionReal': results(['teaching-diagnostic.log', 'material-diagnostic.log'])}
jvm = collections.Counter()
for path in (ROOT / 'client/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    root = ET.parse(path).getroot()
    jvm.update({k: int(root.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')})
    target = QA / 'jvm' / path.name
    target.parent.mkdir(exist_ok=True)
    shutil.copy2(path, target)
summary['jvmApp'] = jvm
lint_path = ROOT / 'client/app/build/reports/lint-results-debug.xml'
lint = ET.parse(lint_path).getroot()
summary['lint'] = collections.Counter(i.get('severity') for i in lint.findall('issue'))
shutil.copy2(lint_path, QA / 'lint.xml')
summary['delivery'] = {'checked': 10, 'passed': sum(r['passed'] for r in json.loads((QA / 'exact-delivery-hashes.json').read_text(encoding='utf-8')))}
(QA / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k: v['totals'] if 'totals' in v else v for k, v in summary.items()}, ensure_ascii=False, indent=2))
