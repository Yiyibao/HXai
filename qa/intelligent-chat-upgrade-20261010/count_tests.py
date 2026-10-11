from pathlib import Path
import json, xml.etree.ElementTree as ET
root=Path.cwd(); report={}
for mod in sorted((root/'client').iterdir()):
 files=list((mod/'build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
 if not files: continue
 stats={k:0 for k in ['tests','failures','errors','skipped']}
 for f in files:
  n=ET.parse(f).getroot()
  for k in stats: stats[k]+=int(n.attrib.get(k,0))
 report[mod.name]=stats
print(json.dumps(report,ensure_ascii=False))
(root/'qa/intelligent-chat-upgrade-20261010/native-test-counts.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
