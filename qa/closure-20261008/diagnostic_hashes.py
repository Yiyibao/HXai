"""Read hashes only from the two known personal diagnostics roots; export no content."""
import argparse, json, subprocess
from pathlib import Path
QA=Path(__file__).resolve().parent
ADB=QA.parents[1]/'.local/android-sdk/platform-tools/adb.exe'
p=argparse.ArgumentParser(); p.add_argument('name'); args=p.parse_args()
def adb(*args):
    return subprocess.run([str(ADB),'-s','emulator-5554',*args],capture_output=True,timeout=30)
records={}
for root in ('no_backup/diagnostics','cache/diagnostic-exports'):
    listing=adb('shell','run-as','org.mathstudy.agent.debug','find',root,'-type','f')
    if listing.returncode:
        assert b'No such file' in listing.stderr+listing.stdout
        continue
    for path in listing.stdout.decode().splitlines():
        assert path.startswith(root+'/') and '..' not in path
        value=adb('shell','run-as','org.mathstudy.agent.debug','sha256sum',path)
        assert value.returncode==0
        records[path]=value.stdout.decode().split()[0]
out=QA/(args.name+'.json'); assert not out.exists()
out.write_text(json.dumps(records,indent=2),encoding='utf-8')
print('hash-only files:',len(records))
