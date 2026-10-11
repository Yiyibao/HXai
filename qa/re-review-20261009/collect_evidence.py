import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[2]
out = Path(__file__).resolve().parent
summary = {}
for module in ['app', 'ai', 'common', 'document', 'oauth', 'search', 'speech', 'workspace']:
    files = sorted((root / 'client' / module / 'build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    stamps = []
    target = out / 'jvm-results' / module
    target.mkdir(parents=True, exist_ok=True)
    for path in files:
        suite = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, 0))
        stamps.append(suite.get('timestamp'))
        shutil.copy2(path, target / path.name)
    summary[module] = dict(counts, suites=len(files), timestamps=sorted(set(stamps)))
(out / 'jvm-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
sources = [
 'client/flutter_ui/lib/providers/study_providers.dart',
 'client/flutter_ui/lib/pages/chat_page.dart',
 'client/flutter_ui/lib/pages/conversation_drawer.dart',
 'client/app/src/main/java/me/rerere/rikkahub/feature/material/DocumentServiceTools.kt',
 'client/app/src/main/java/me/rerere/rikkahub/feature/material/DocumentServiceRepository.kt',
 'client/app/src/main/java/me/rerere/rikkahub/flutter/StudyHostFacade.kt',
 'client/app/src/main/java/me/rerere/rikkahub/feature/memory/LearningMemoryStore.kt',
 'client/app/src/androidTest/java/me/rerere/rikkahub/service/BackupReliabilityTest.kt',
]
hashes = {}
for relative in sources:
    path = root / relative
    hashes[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    snapshot = out / 'source-evidence' / Path(relative).name
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    snapshot.write_text('\n'.join(f'{i}: {line}' for i, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1)), encoding='utf-8')
(out / 'source-hashes.json').write_text(json.dumps(hashes, indent=2), encoding='utf-8')
lint = root / 'client/app/build/reports/lint-results-debug.txt'
if lint.exists():
    shutil.copy2(lint, out / lint.name)
print(json.dumps({module: {k: v for k, v in value.items() if k != 'timestamps'} for module, value in summary.items()}, indent=2))
