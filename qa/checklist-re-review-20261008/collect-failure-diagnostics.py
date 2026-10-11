"""Extract only allowlisted numeric/enum diagnostics in this failed QA time window."""
import json
import subprocess
from datetime import datetime
from pathlib import Path
QA = Path(__file__).resolve().parent
ROOT = QA.parents[1]
records = json.loads((QA/'additional-results.json').read_text(encoding='utf-8-sig'))
failures = []
for record in records:
    if record['passed']:
        continue
    started = int(datetime.fromisoformat(record['started']).timestamp()*1000)
    ended = started + int(record['seconds']*1000)
    result = subprocess.run([str(ROOT/'.local/android-sdk/platform-tools/adb.exe'),'-s','emulator-5554','exec-out','run-as','org.mathstudy.agent.debug','cat','no_backup/diagnostics/events.json'],capture_output=True)
    events = json.loads(result.stdout)['events'] if result.returncode == 0 else []
    filtered = [{key:event[key] for key in ('time','stage','result','status','durationMs') if key in event} for event in events if started <= event.get('time',0) <= ended]
    failures.append({'test':record['name'],'started':record['started'],'seconds':record['seconds'],'events':filtered,'collection_status':'collected' if filtered else 'unavailable_or_evicted','reason':'HTTP status is evidence; no response body or provider cause inferred'})
(QA/'failure-diagnostics.json').write_text(json.dumps(failures,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(failures,ensure_ascii=False,indent=2))
