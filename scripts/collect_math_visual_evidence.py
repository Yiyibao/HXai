"""Copy synthetic math QA evidence from the debug app without copying settings or credentials."""
from pathlib import Path
import subprocess
import json

root = Path(__file__).resolve().parents[1]
adb = root / '.local/android-sdk/platform-tools/adb.exe'
destination = root / 'qa/math-visual-20261010'
destination.mkdir(parents=True, exist_ok=True)
for name in ['math-visual-default.png', 'math-visual-chat.png', 'math-visual-chat-delivery.png', 'math-visual-live-0.png', 'math-visual-live-1.png', 'math-visual-live.json']:
    result = subprocess.run([str(adb), 'exec-out', 'run-as', 'org.hxsi.agent.debug', 'cat', 'cache/' + name], capture_output=True)
    valid = result.returncode == 0
    if name.endswith('.png'):
        valid = valid and result.stdout.startswith(b'\x89PNG\r\n\x1a\n')
    else:
        try:
            payload = json.loads(result.stdout)
            valid = valid and isinstance(payload, list) and len(payload) > 0
        except (ValueError, UnicodeDecodeError):
            valid = False
    if not valid:
        print(name + ': not available')
        continue
    (destination / name).write_bytes(result.stdout)
    print(name + ': ' + str(len(result.stdout)) + ' bytes')
