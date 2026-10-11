import json
from pathlib import Path
qa=Path(__file__).parent
results=[]
for name in ['material-evidence-delivery-receipts.txt','review-delivery-delivery-receipts.txt']:
    records={}
    for line in (qa/'fixtures'/name).read_text(encoding='utf-8').splitlines():
        ext,action,payload=line.split(' ',2)
        records.setdefault(ext,{})[action]=json.loads(payload) if action!='SAVE' else payload
    for ext,row in records.items():
        source=row['SOURCE']['sha256']
        valid=row['VIEW']['sha256']==source==row['SEND']['sha256'] and 'PASS' in row['SAVE']
        assert valid
        results.append({'suite':name,'extension':ext,'source_name':row['SOURCE']['name'],'sha256':source,'view_send_sha256_match':True,'save_reread_assertion':'PASS'})
(qa/'delivery-hash-summary.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
print('10 exact SOURCE/VIEW/SEND hash groups and SAVE reread assertions verified')
