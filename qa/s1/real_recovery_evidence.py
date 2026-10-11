"""Only explicitly selected [QA] conversations; no credentials or raw history output."""
from contextlib import closing
import argparse, hashlib, io, json, re, sqlite3, subprocess, tarfile, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
ADB = ROOT / '.local/android-sdk/platform-tools/adb.exe'
PACKAGE = 'org.mathstudy.agent.debug'
def adb(*args):
    return subprocess.check_output([str(ADB), '-s', 'emulator-5554', *args], timeout=40)
def inspect(cid):
    data = adb('exec-out', 'run-as', PACKAGE, 'tar', 'cf', '-', 'databases/rikka_hub', 'databases/rikka_hub-wal')
    with tempfile.TemporaryDirectory() as folder:
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            for name in ('databases/rikka_hub', 'databases/rikka_hub-wal'):
                Path(folder, Path(name).name).write_bytes(archive.extractfile(name).read())
        with closing(sqlite3.connect(str(Path(folder, 'rikka_hub')))) as conn:
            title, queue = conn.execute('select title, queue_state from ConversationEntity where id=?', (cid,)).fetchone()
            assert title.startswith('[QA] real ')
            messages=[]
            for raw, selected in conn.execute('select messages, select_index from message_node where conversation_id=? order by node_index', (cid,)):
                m=json.loads(raw)[selected]
                parts=m['parts']
                text=''.join(p.get('text','') for p in parts)
                messages.append(dict(id=m['id'], role=m['role'], state=m.get('generationState'), characters=len(text),
                    sha256=hashlib.sha256(text.encode()).hexdigest(), tools=[dict(call_id=p.get('toolCallId'), name=p.get('toolName'),
                        execution_state=p.get('executionState'), approval_type=p.get('approvalState',{}).get('type'), output_sha256=hashlib.sha256(json.dumps(p.get('output',[]),sort_keys=True).encode()).hexdigest(),
                        unknown='"status":"unknown"' in json.dumps(p.get('output',[]),ensure_ascii=False).replace('\\"','"')) for p in parts if 'toolCallId' in p]))
            q=json.loads(queue)
            return dict(id=cid,title=title,messages=messages, queue=dict(paused=q.get('paused'), inputs=[dict(id=p['id'],
                parts_sha256=hashlib.sha256(json.dumps(p['parts'],sort_keys=True).encode()).hexdigest()) for p in q.get('messages',[])]))
if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('id'); parser.add_argument('--output',type=Path); args=parser.parse_args()
    result=inspect(args.id); data=json.dumps(result,ensure_ascii=False,indent=2)+'\n'
    if args.output: args.output.write_text(data,encoding='utf-8')
    print(data)


