import json
from pathlib import Path
p=Path('qa/comprehensive-review-20261008/fixtures/s2-real-memory.json')
d=json.loads(p.read_text(encoding='utf-8'))
s=d['state']; sources={x['id']:x for x in s['sources']}
print({k:d[k] for k in ['positive_source_count','covered_positive_sources','false_positive_items']})
for m in s['items']:
 print(json.dumps({k:m.get(k) for k in ['type','topicKey','factKey','content','evidenceType','status']},ensure_ascii=False))
