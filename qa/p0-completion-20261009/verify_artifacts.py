from pathlib import Path
import json, hashlib
from docx import Document
from pptx import Presentation
from openpyxl import load_workbook
from pypdf import PdfReader
root=Path(__file__).parent; rich=root/'collected/p0-rich-output'; live=root/'collected/p0-live-dispatch'
doc=Document(rich/'rich-output.docx')
assert any('Bold edited' in p.text for p in doc.paragraphs)
assert len(doc.inline_shapes)==1 and len(doc.tables)==1
ppt=Presentation(rich/'rich-output.pptx'); assert len(ppt.slides)==2
assert ppt.slides[0].shapes.title.text=='Edited slide title'
book=load_workbook(rich/'rich-output.xlsx'); sheet=book['Data']
assert sheet['A2'].value==4 and sheet['B2'].value=='=A2*3' and sheet['C2'].value=='001'
assert len(sheet._images)==1
result=load_workbook(live/'output.xlsx',data_only=False)
rows=[list(row) for s in result for row in s.iter_rows(values_only=True)]
text=json.dumps(rows)
assert all(f'PAGE_{i}' in text for i in range(1,8))
for i in range(1,8):
    assert [f'PAGE_{i}',i*3,i*7] in rows
mixed=load_workbook(root/'collected/p0-live-mixed/output.xlsx',data_only=False)
mixed_rows=[list(row) for s in mixed for row in s.iter_rows(values_only=True)]
for i in range(1,8):
    assert [f'PAGE_{i}',i*3,i*7] in mixed_rows
assert len(PdfReader(live/'source-seven-scanned.pdf').pages)==7
assert len(PdfReader(rich/'image-preserved.pdf').pages)==1
hashes={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((root/'collected').rglob('*')) if p.is_file()}
(root/'artifact-verification.json').write_text(json.dumps({'word_reopened':True,'ppt_reopened':True,'excel_reopened':True,'formula_recalculation':'required_in_office','live_workbook_rows':rows,'mixed_visual_workbook_rows':mixed_rows,'all_quantities_and_prices_match_source':True,'hashes':hashes},indent=2),encoding='utf-8')
print('Office outputs reopen; live Excel contains PAGE_1–PAGE_7; PDF page counts verified')
