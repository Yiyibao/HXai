"""Capability presence probe; failure proves ABSENT only, not an exploit."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
required = ['client/app/src/main/java/me/rerere/rikkahub/feature/agent/ExtensionInstallationStore.kt',
            'client/flutter_ui/lib/pages/extensions_page.dart', 'server/skill-sandbox/sandbox_service.py']
missing = [p for p in required if not (root / p).is_file()]
for p in required:
    print(('ABSENT' if p in missing else 'PRESENT') + ': ' + p)
raise SystemExit(bool(missing))
