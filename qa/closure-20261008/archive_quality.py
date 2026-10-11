import json, shutil, xml.etree.ElementTree as ET
from pathlib import Path
QA=Path(__file__).resolve().parent; ROOT=QA.parents[1]
out=QA/'final-jvm'; out.mkdir(exist_ok=False)
counts={}
for name,folder in [('app',ROOT/'client/app/build/test-results/testDebugUnitTest'),('ai',ROOT/'client/ai/build/test-results/testDebugUnitTest'),('probes',QA/'probe-xml')]:
    dest=out/name; dest.mkdir()
    values=dict(tests=0,failures=0,errors=0,skipped=0)
    for f in folder.glob('*.xml'):
        shutil.copyfile(f,dest/f.name); xml=ET.parse(f).getroot()
        for key in values: values[key]+=int(xml.get(key,0))
    counts[name]=values
(out/'counts.json').write_text(json.dumps(counts,indent=2))
print(json.dumps(counts))
