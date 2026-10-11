"""Read-only Android evidence summaries. Never emits credentials or message text.
Copy snapshots after generation stops; an in-flight copy is observational only.
"""
import argparse, hashlib, io, json, re, sqlite3, subprocess, tarfile, tempfile
from collections import Counter
from contextlib import closing
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
ADB = ROOT / '.local/android-sdk/platform-tools/adb.exe'
PACKAGE = 'org.mathstudy.agent.debug'
def adb(*args):
    return subprocess.check_output([str(ADB), *args], timeout=40)
def snapshot():
    data = adb('exec-out', 'run-as', PACKAGE, 'tar', 'cf', '-', 'databases/rikka_hub', 'databases/rikka_hub-wal')
    with tempfile.TemporaryDirectory() as folder:
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            for member in archive.getmembers():
                if member.name in ('databases/rikka_hub', 'databases/rikka_hub-wal'):
                    Path(folder, Path(member.name).name).write_bytes(archive.extractfile(member).read())
        with closing(sqlite3.connect(str(Path(folder, 'rikka_hub')))) as conn:
            conn.row_factory = sqlite3.Row
            integrity = 'NOT_CHECKED_HOST_FTS_TOKENIZER_INCOMPATIBLE'
            results=[]
            for row in conn.execute('select id, profile_id, nodes from ConversationEntity'):
                nodes=json.loads(row['nodes'])
                # Some builds keep bodies in message_node rather than the legacy nodes column.
                separate=conn.execute('select messages, select_index from message_node where conversation_id=? order by node_index',(row['id'],)).fetchall()
                if separate: nodes=[{'messages':json.loads(n['messages']), 'selectIndex':n['select_index']} for n in separate]
                messages=[]
                for node in nodes:
                    for msg in node.get('messages',[]):
                        text=''.join(p.get('text','') for p in msg.get('parts',[]) if isinstance(p,dict))
                        messages.append({'id':msg.get('id'),'role':msg.get('role'),'characters':len(text),'sha256':hashlib.sha256(text.encode()).hexdigest(),'markers':[m for m in ('QA_CANCEL_A','QA_SWITCH_A','QA_B_ONLY','QA_FOLLOWUP_A','QA_RESTART_A','QA_KILL_A') if m in text]})
                if any(m['markers'] for m in messages): results.append({'id':row['id'],'profile_id':row['profile_id'],'messages':messages})
    logs=adb('logcat','-d','-s','ResponseAPI:D','AndroidRuntime:E').decode(errors='replace')
    events = Counter()
    requests = []
    for line in logs.splitlines():
        event = re.search(r'onEvent: .*?/(\S+) ', line)
        if event:
            events[event[1]] += 1
        if 'streamText: ' in line:
            try:
                body = json.loads(line.split('streamText: ', 1)[1])
            except ValueError:
                continue  # Android may truncate long log lines; never infer missing fields.
            input_text = json.dumps(body.get('input'), ensure_ascii=False)
            requests.append({
                'model': body.get('model'), 'stream': body.get('stream'),
                'store': body.get('store'), 'reasoning': body.get('reasoning'),
                'input_markers': [marker for marker in (
                    'QA_CANCEL_A', 'QA_SWITCH_A', 'QA_B_ONLY',
                    'QA_FOLLOWUP_A', 'QA_RESTART_A', 'QA_KILL_A') if marker in input_text],
            })
    return {'integrity':integrity,'conversations':results,
            'responses_events':dict(events), 'parsed_requests':requests,
            'fatal_exceptions':logs.count('FATAL EXCEPTION')}
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path);args=parser.parse_args()
    result=snapshot();output=json.dumps(result,ensure_ascii=False,indent=2)+'\n'
    if args.output:args.output.write_text(output,encoding='utf-8')
    print(output)
