"""Read a consistent SQLite snapshot in RAM; export only synthetic P0-live messages."""
import subprocess, sqlite3, struct, json
from pathlib import Path
root = Path(__file__).resolve().parents[2]
adb = str(root / '.local/android-sdk/platform-tools/adb.exe')
def read(name):
    return subprocess.run([adb,'exec-out','run-as','org.mathstudy.agent.debug','cat','databases/'+name],capture_output=True,check=True).stdout
base = bytearray(read('rikka_hub')); wal = read('rikka_hub-wal')
assert wal == read('rikka_hub-wal'), 'Snapshot changed; retry'
size = struct.unpack('>I',wal[8:12])[0]
frames = []; commit = 0
for off in range(32,len(wal)-24-size+1,24+size):
    page,total = struct.unpack('>II',wal[off:off+8]); frames.append((page,wal[off+24:off+24+size]))
    if total: commit = len(frames)
for page,content in frames[:commit]:
    end = page*size
    if len(base)<end: base.extend(b'\0'*(end-len(base)))
    base[(page-1)*size:end]=content
base[18:20]=b'\1\1'
db=sqlite3.connect(':memory:'); db.deserialize(bytes(base))
convs=db.execute("SELECT DISTINCT c.id,c.profile_id FROM ConversationEntity c JOIN message_node m ON m.conversation_id=c.id WHERE c.title LIKE 'P0-live-%' OR m.messages LIKE '%source-seven-scanned.pdf%'").fetchall()
result=[]
for ident,owner in convs:
    nodes=db.execute('SELECT messages FROM message_node WHERE conversation_id=? ORDER BY node_index',(ident,)).fetchall()
    result.append({'id':ident,'owner':owner,'nodes':[json.loads(row[0]) for row in nodes]})
out=Path(__file__).parent/'live-progress.json'; out.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
for conv in result:
    print('Synthetic conversation',conv['id'])
    for node in conv['nodes']:
        for message in node:
            print(message.get('role'),message.get('generationState'),str(message.get('parts'))[:1800])
