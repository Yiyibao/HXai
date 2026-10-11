from pathlib import Path
import zipfile,json,hashlib,xml.etree.ElementTree as ET
qa=Path(__file__).parent
ns={'m':'http://schemas.openxmlformats.org/officeDocument/2006/math','w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
with zipfile.ZipFile(qa/'word-real-input.docx') as z:
    source=[z.read(n) for n in z.namelist() if n.startswith('word/media/')]
with zipfile.ZipFile(qa/'word-real-failed.docx') as z:
    output=[z.read(n) for n in z.namelist() if n.startswith('word/media/')]
    doc=ET.fromstring(z.read('word/document.xml'))
    text=lambda p: ''.join(doc.find(p,ns).itertext())
    result={'fraction_numerator':text('.//m:f/m:num'),'fraction_denominator':text('.//m:f/m:den'),'power_base':text('.//m:sSup/m:e'),'power_exponent':text('.//m:sSup/m:sup'),'image_count':len(output),'original_image_bytes_preserved':source==output,'table_count':len(doc.findall('.//w:tbl',ns)),'automated_suite_status':'FAILED','failure_reason':'literal text assertion rejects redundant numerator parentheses','image_generation_probe_executed':False}
    assert result['fraction_numerator']=='(x+1)' and result['fraction_denominator']=='2' and result['power_base']=='y' and result['power_exponent']=='3'
    assert result['original_image_bytes_preserved'] and result['image_count']==1 and result['table_count']==1
    result['output_sha256']=hashlib.sha256((qa/'word-real-failed.docx').read_bytes()).hexdigest()
(qa/'word-structural-check.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result,indent=2))
