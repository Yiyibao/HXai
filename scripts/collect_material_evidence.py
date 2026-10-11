"""Collect only synthetic material fixtures and screenshots, never application settings."""
import argparse
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--real', action='store_true')
args = parser.parse_args()
if not args.serial.startswith('emulator-') or not args.serial[9:].isdigit():
    raise SystemExit('Only an emulator may be used.')
adb = root / '.local/android-sdk/platform-tools/adb.exe'
qa = root / 'qa/material'
qa.mkdir(parents=True, exist_ok=True)

def read(path):
    return subprocess.check_output([str(adb), '-s', args.serial, 'exec-out', 'run-as', 'org.mathstudy.agent.debug', 'cat', path])

names = subprocess.check_output([str(adb), '-s', args.serial, 'shell', 'run-as', 'org.mathstudy.agent.debug', 'ls', 'cache/material-evidence']).decode('utf-8').splitlines()
for name in names:
    if pathlib.PurePosixPath(name).name != name or name.startswith('.'):
        raise ValueError('Invalid evidence filename')
    (qa / name).write_bytes(read('cache/material-evidence/' + name))
for name in ['material-page.png', 'material-profile-b.png', 'material-save-dialog.png', 'material-share-dialog.png']:
    (qa / name).write_bytes(read('cache/' + name))
try:
    (qa / 'material-share-ui.txt').write_bytes(read('cache/material-share-ui.txt'))
except subprocess.CalledProcessError:
    pass
if args.real:
    (qa / 'real-model.md').write_bytes(read('cache/material-real-model.md'))
print('Synthetic fixtures and UI evidence collected.')
