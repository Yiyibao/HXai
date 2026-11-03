"""Archive this increment's synthetic evidence; never edit the original delivery."""
from pathlib import Path
import hashlib, json, subprocess, shutil, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def git(*args): return subprocess.check_output(['git', '-C', str(ROOT), *args], text=True, encoding='utf-8').strip()
def write(path, value): (ROOT / path).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

# Only files actually regenerated in this round, not inherited Pro logs.
replay = Path(r'C:\Users\Hfff\AppData\Local\Temp\hxai-phase2-replay-20261012\evidence\tests')
target = ROOT / 'HXai_CHANGE_REVIEW/PHASE2-SAFETY/replay-copy'
target.mkdir(exist_ok=True)
for name in ['document_contract_FINAL.log', 'runtime_math_FINAL.log', 'document_baseline_EXPECTED_FAIL.log',
             'document_baseline_FIXED.log', 'delivery_guards_FINAL.log', 'protocol_demo_FINAL.log']:
    source = replay / name
    if source.exists():
        destination = target / name
        if destination.exists(): assert digest(destination) == digest(source), 'Refusing to replace earlier raw evidence'
        else: shutil.copy2(source, destination)

trace = {'branch': git('branch', '--show-current'), 'head': git('rev-parse', 'HEAD'),
         'base': '5d826d5d7d8755ed42db834d6cd6dc52ae914c69', 'status': git('status', '--porcelain=v1')}
changed = set(git('diff', trace['base'], '--name-only', '--', 'client', 'server', 'scripts', 'qa/pro-phase2-20261012').splitlines())
changed.update(git('ls-files', '--others', '--exclude-standard', '--', 'client', 'server', 'scripts', 'qa/pro-phase2-20261012').splitlines())
trace['source_sha256'] = {p: digest(ROOT / p) for p in sorted(changed) if (ROOT / p).is_file()}
write('source-map.json', trace)

all_runs = []
for feature in ['PHASE2-SAFETY', 'AGENT-01-MODEL-02']:
    directory = ROOT / 'HXai_CHANGE_REVIEW' / feature
    runs = []
    for record in sorted(directory.glob('*.json')):
        if record.name == 'TEST_RESULTS.json': continue
        value = json.loads(record.read_text(encoding='utf-8'))
        if 'command' in value: runs.append(value)
    logs = {str(p.relative_to(ROOT)): digest(p) for p in directory.rglob('*.log')}
    write(f'HXai_CHANGE_REVIEW/{feature}/TEST_RESULTS.json', {'feature': feature, 'runs': runs, 'raw_log_sha256': logs,
        'notes': ['Baseline counterexamples intentionally FAIL; not counted as repaired PASS.',
                  'native-final.log was interrupted with no exit record. Do not infer completed APK build.',
                  'gradle-second.log is an intermediate PASS with source changes during build; final source-bound run is separate.',
                  'Copied Pro runner still fails on Windows; historical inherited Kotlin logs are excluded.']})
    all_runs.extend(runs)
write('HXai_FULL_REVIEW/TEST_RUNS.json', {'runs': all_runs, 'scope': 'phase2 architecture/admission + service safety, not entire app acceptance'})
write('HXai_FULL_REVIEW/commands.json', {'runs': all_runs})

reports = ROOT / 'HXai_CHANGE_REVIEW/AGENT-01-MODEL-02/final-reports'
reports.mkdir(exist_ok=True)
counts = []
for module in ['app', 'ai']:
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in (ROOT / f'client/{module}/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        tree = ET.parse(path).getroot()
        for key in totals: totals[key] += int(tree.attrib.get(key, 0))
        shutil.copy2(path, reports / f'{module}-{path.name}')
    counts.append(dict(module=module, **totals))
lint = ROOT / 'client/app/build/reports/lint-results-debug.xml'
if lint.exists():
    shutil.copy2(lint, reports / lint.name)
    issues = ET.parse(lint).getroot().findall('issue')
    lint_counts = {severity: sum(i.attrib.get('severity') == severity for i in issues) for severity in ['Error', 'Fatal', 'Warning', 'Information']}
else: lint_counts = None
write('HXai_CHANGE_REVIEW/AGENT-01-MODEL-02/report-summary.json', {'jvm': counts, 'lint': lint_counts})

artifacts = {str(path.relative_to(ROOT)): digest(path) for path in (ROOT / 'client/app/build/outputs').rglob('*.apk')}
write('HXai_CHANGE_REVIEW/AGENT-01-MODEL-02/artifacts.json', artifacts)
print(json.dumps({'jvm': counts, 'lint': lint_counts, 'apks': len(artifacts)}, ensure_ascii=False))
