"""Independent host readback of the synthetic PPT emitted by OfficeSafetyRuntimeTest."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
folder = root / 'HXai_CHANGE_REVIEW/FILE-05-OOXML-PPT-20261012/artifacts'

def read(name):
    with zipfile.ZipFile(folder / name) as z:
        assert z.testzip() is None
        return {entry.filename: z.read(entry) for entry in z.infolist()}

source, output = read('source.pptx'), read('output.pptx')
assert source.keys() == output.keys()
for parts in [source, output]:
    for node in ET.fromstring(parts['[Content_Types].xml']):
        if 'PartName' in node.attrib:
            assert node.attrib['PartName'].lstrip('/') in parts
changed = [name for name in source if source[name] != output[name]]
assert changed == ['ppt/slides/slide9.xml']
p = 'http://schemas.openxmlformats.org/presentationml/2006/main'
r = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
a = 'http://schemas.openxmlformats.org/drawingml/2006/main'
relations = {rel.attrib['Id']: rel.attrib['Target'] for rel in ET.fromstring(output['ppt/_rels/presentation.xml.rels'])}
order = [relations[s.attrib['{' + r + '}id']] for s in ET.fromstring(output['ppt/presentation.xml']).find('{' + p + '}sldIdLst')]
assert order == ['slides/slide9.xml', 'slides/slide1.xml']
slide = ET.fromstring(output['ppt/' + order[0]])
assert slide.attrib['show'] == '0'
assert slide.find('.//{' + a + '}t').text == 'Edited logical first'
hashes = {name + '_sha256': hashlib.sha256((folder / (name + '.pptx')).read_bytes()).hexdigest() for name in ['source', 'output']}
assert hashes == json.loads((folder / 'hashes.json').read_text())
result = dict(status='PASS', parser='Python ElementTree + zipfile independent readback', changed_parts=changed,
              logical_order=order, hidden_first_slide=True, **hashes)
(folder.parent / 'ARTIFACT_READBACK.json').write_text(json.dumps(result, indent=2))
print(json.dumps(result, indent=2))
