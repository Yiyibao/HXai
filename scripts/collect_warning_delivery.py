"""Collect exact named synthetic sources and match each delivery to its own source."""
from pathlib import Path
import subprocess
import hashlib
import json
import re
from datetime import datetime
ROOT=Path(__file__).resolve().parents[1]
QA=ROOT/'qa/warnings-review-20261008'
ADB=ROOT/'.local/android-sdk/platform-tools/adb.exe'
started=datetime.fromisoformat(json.loads((QA/'emulator-results.json').read_text(encoding='utf-8-sig'))[0]['started']).timestamp()
def adb(*args):return subprocess.run([str(ADB),'-s','emulator-5554',*args],capture_output=True)
def export(remote,target):
 stamp=adb('shell','run-as','org.mathstudy.agent.debug','stat','-c','%Y',remote)
 assert stamp.returncode==0 and int(stamp.stdout.strip())>=started, f'Stale/missing fixture: {remote}'
 result=adb('exec-out','run-as','org.mathstudy.agent.debug','cat',remote)
 assert result.returncode==0
 target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(result.stdout)
 return result.stdout
results=[]
for group,remote in [('artifact','review-delivery'),('material','material-evidence')]:
 receipt=export(f'cache/{remote}/delivery-receipts.txt',QA/'delivery'/group/'delivery-receipts.txt').decode('utf-8')
 sources={}
 for line in receipt.splitlines():
  m=re.match(r'(md|docx|xlsx|pptx|pdf) SOURCE (.*)',line)
  if m:sources[m[1]]=json.loads(m[2])
 assert len(sources)==5, f'Incomplete source receipts: {group}'
 for ext,source in sources.items():
  name=source['name'];assert re.fullmatch(r'QA交付-[a-f0-9-]{36}\.'+ext,name),name
  raw=export(f'cache/{remote}/source-{ext}.{ext}',QA/'delivery'/group/f'source.{ext}')
  path='/sdcard/Download/'+name
  delivery=adb('exec-out','cat',path)
  assert delivery.returncode==0,f'Missing exact delivery: {name}'
  (QA/'delivery'/group/f'delivered.{ext}').write_bytes(delivery.stdout)
  view=next(json.loads(x.split(' VIEW ',1)[1]) for x in receipt.splitlines() if x.startswith(ext+' VIEW '))
  send=next(json.loads(x.split(' SEND ',1)[1]) for x in receipt.splitlines() if x.startswith(ext+' SEND '))
  digest=lambda value:hashlib.sha256(value).hexdigest()
  value={'group':group,'extension':ext,'exact_name':name,'source_sha256':digest(raw),
         'delivered_sha256':digest(delivery.stdout),'view_sha256':view['sha256'],'send_sha256':send['sha256'],
         'recorded_sha256':source['sha256']}
  value['passed']=len({value[k] for k in ('source_sha256','delivered_sha256','view_sha256','send_sha256','recorded_sha256')})==1
  results.append(value)
  (QA/'exact-delivery-hashes.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'checked':len(results),'passed':sum(x['passed'] for x in results)},indent=2))
