"""Record individual evidence, preserving absence and negative outcomes."""
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile
import hashlib

QA = Path(__file__).resolve().parent
memory = QA / 'fixtures/s2-real-memory.json'
if memory.exists():
    data = json.loads(memory.read_text(encoding='utf-8'))
    texts = data['sources']
    sources = data['state']['sources']
    # New synthetic conversation starts from empty state. The production reconcile
    # reducer appends sources in selected USER-node order; correlate, then verify
    # each recorded Unicode length. This is an explicit inference from source order.
    if len(texts) != 30 or len(sources) != 30:
        raise RuntimeError('Cannot establish one-to-one source order')
    rows = []
    for index, (text, source) in enumerate(zip(texts, sources)):
        if source.get('spanEnd') != len(text):
            raise RuntimeError(f'Source span/order mismatch at {index+1}')
        items = [item for item in data['state']['items'] if source['id'] in item['sourceIds']]
        positive = index < data['positive_source_count']
        rows.append({'index':index+1,'input':text,'label':'positive' if positive else 'negative','source_id':source['id'],'message_id':source['messageId'],'items':[{'id':item['id'],'content':item['content'],'type':item['type'],'status':item.get('status','CANDIDATE'),'evidenceType':item['evidenceType']} for item in items], 'source_result':'retained' if items else 'omitted' if positive else 'correctly_excluded'})
    result = {'mapping_basis':'fresh conversation; production USER source insertion order; all 30 spanEnd lengths checked; no fingerprint secret exported','rows':rows,'positive_retained':sum(row['label']=='positive' and bool(row['items']) for row in rows),'negative_included':sum(row['label']=='negative' and bool(row['items']) for row in rows)}
    (QA / 'memory-source-audit.json').write_text(json.dumps(result, ensure_ascii=False, indent=2),encoding='utf-8')
    print(json.dumps({key:result[key] for key in ('positive_retained','negative_included')}))

transcript = QA / 'fixtures/material-real-model.md'
if transcript.exists():
    text = transcript.read_text(encoding='utf-8')
    start = text.find('图片服务配置')
    probe = text[start:] if start >= 0 else 'Probe evidence absent; do not infer success from outer test.'
    status = 'NOT_RUN' if start < 0 else 'missing_configuration' if '未选定图片生成模型' in probe or '未配置' in probe else 'inspect_actual_probe_result'
    (QA / 'image-probe.json').write_text(json.dumps({'status':status,'evidence':probe},ensure_ascii=False,indent=2),encoding='utf-8')

inp = QA / 'fixtures/word-input.docx'
out = QA / 'fixtures/word-output.docx'
if inp.exists() and out.exists():
    ns = {'m':'http://schemas.openxmlformats.org/officeDocument/2006/math','w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
    with ZipFile(inp) as source, ZipFile(out) as output:
        root = ET.fromstring(output.read('word/document.xml'))
        media = lambda archive:{name:hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist() if name.startswith('word/media/')}
        source_media, output_media = media(source), media(output)
        result = {'fractions':[{'numerator':''.join(node.find('m:num',ns).itertext()),'denominator':''.join(node.find('m:den',ns).itertext())} for node in root.findall('.//m:f',ns)],'powers':[{'base':''.join(node.find('m:e',ns).itertext()),'exponent':''.join(node.find('m:sup',ns).itertext())} for node in root.findall('.//m:sSup',ns)],'table_count':len(root.findall('.//w:tbl',ns)),'source_media':source_media,'output_media':output_media,'original_image_preserved':bool(source_media) and sorted(source_media.values()) == sorted(output_media.values()),'negative_image_cases':'NOT_RUN'}
        (QA / 'word-structure.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(result,ensure_ascii=False))
