import difflib
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

out = Path(__file__).resolve().parent
root = out.parent.parent
summary = {}
for module in ['app', 'ai']:
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    stamps = []
    target = out / 'jvm-results' / module
    target.mkdir(parents=True, exist_ok=True)
    for path in sorted((root / 'client' / module / 'build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, 0))
        stamps.append(suite.get('timestamp'))
        shutil.copy2(path, target / path.name)
    summary[module] = dict(counts, timestamps=sorted(set(stamps)))
(out / 'jvm-summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
sources = [
    'client/flutter_ui/lib/providers/study_providers.dart',
    'client/flutter_ui/lib/pages/chat_page.dart',
    'client/flutter_ui/lib/pages/conversation_drawer.dart',
    'client/app/src/main/java/me/rerere/rikkahub/feature/material/DocumentServiceTools.kt',
]
patches = []
hashes = {}
for relative in sources:
    path = root / relative
    hashes[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    old = (root / 'qa/re-review-20261009/source-evidence' / path.name).read_text(encoding='utf-8')
    old = '\n'.join(re.sub(r'^\d+: ', '', line) for line in old.splitlines()) + '\n'
    new = path.read_text(encoding='utf-8')
    patches.extend(difflib.unified_diff(old.splitlines(True), new.splitlines(True), fromfile='reviewed/' + relative, tofile='fixed/' + relative))
    target = out / 'source-evidence' / path.name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('\n'.join(f'{i}: {line}' for i, line in enumerate(new.splitlines(), 1)), encoding='utf-8')
(out / 'fix.patch').write_text(''.join(patches), encoding='utf-8')
(out / 'source-hashes.json').write_text(json.dumps(hashes, indent=2), encoding='utf-8')
lint = root / 'client/app/build/reports/lint-results-debug.txt'
if lint.exists():
    shutil.copy2(lint, out / lint.name)
print(json.dumps({module: {k: v for k, v in value.items() if k != 'timestamps'} for module, value in summary.items()}, indent=2))
