import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

QA = Path(__file__).parent
fixtures = QA / 'fixtures/material-evidence'
ns = {'m': 'http://schemas.openxmlformats.org/officeDocument/2006/math', 'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
with ZipFile(fixtures / 'word-vision-output.docx') as out, ZipFile(fixtures / 'fidelity.docx') as src:
    root = ET.fromstring(out.read('word/document.xml'))
    images = lambda z: [hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('word/media/')]
    fraction = [{k: ''.join(n.find('m:' + k, ns).itertext()) for k in ('num', 'den')} for n in root.findall('.//m:f', ns)]
    power = [{k: ''.join(n.find('m:' + k, ns).itertext()) for k in ('e', 'sup')} for n in root.findall('.//m:sSup', ns)]
    tables = len(root.findall('.//w:tbl', ns))
    assert fraction and power and tables > 0
    assert images(src) == images(out) and len(images(out)) == 1
    result = dict(fraction=fraction, power=power, tables=tables, originalImageBytesPreserved=True, sourceImages=images(src), outputImages=images(out))
scan = (fixtures / 'scan-output.md').read_text(encoding='utf-8')
assert '7391' in scan and 'x' in scan and '1' in scan and '2' in scan
result['scanIdentifierAndFractionPresent'] = True
transcript = (QA / 'fixtures/material-real-model.md').read_text(encoding='utf-8')
result['imageProbe'] = 'MISSING_CONFIGURATION' if '未配置图片生成模型' in transcript else 'SEE_TRANSCRIPT'
(QA / 'output-audit.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
