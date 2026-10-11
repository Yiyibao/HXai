import json, subprocess
from pathlib import Path
root = Path(__file__).resolve().parents[2]
identity = json.loads((root / '.local/document-service/credentials/p0-test-20261009.json').read_text())['identity']
code = '''import sqlite3,json
db=sqlite3.connect('/var/lib/mathdoc/tasks.sqlite3')
db.row_factory=sqlite3.Row
rows=db.execute('SELECT id,state FROM jobs WHERE owner=?',(%r,)).fetchall()
print(json.dumps([dict(r) for r in rows]))
''' % identity
result = subprocess.run(['ssh','-i',str(Path.home()/'.ssh/id_ed25519_tencent_blog'),'ubuntu@154.222.25.150','sudo python3 -'],input=code,text=True,capture_output=True,check=True)
print(result.stdout)
