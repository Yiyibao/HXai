"""Independently verify actual Android library-search receipts against archived synthetic source bytes."""
from pathlib import Path, PurePosixPath
import argparse, hashlib, json, struct, tarfile
from pypdf import PdfReader
import pypdf
import xml.etree.ElementTree as ET
import zipfile

def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def fingerprint(owner,query,items):
    h=hashlib.sha256()
    def part(text):
        if text is None:h.update(struct.pack('>i',-1));return
        b=text.encode('utf-16-be',errors='surrogatepass');h.update(struct.pack('>i',len(b)//2));h.update(b)
    for value in ['material_library_search_v1',owner,query,str(len(items))]:part(value)
    for item in sorted(items,key=lambda x:x['id']):
        for value in [item['id'],item['profileId'],item['name'],item['extension'],str(item['size']),item['sha256'],item.get('sourceId'),item['state']]:part(value)
    return h.hexdigest()
def metadata(folder):return [json.loads(p.read_text(encoding='utf-8')) for p in sorted(folder.glob('*.meta.json'))]
def quote_check(hit,owner,item):
    proof=hit['source_receipt'];quote=hit['untrusted_file_data'];loc=proof['locator']
    assert proof['contractVersion']==2 and proof['status']=='VERIFIED' and proof['scope']=='extracted_text_range'
    assert proof['owner']==owner and proof['material_id']==proof['version_id']==item['id']
    assert proof['source_sha256']==item['sha256'] and proof['parent_id']==item.get('sourceId')
    assert proof['quote_sha256']==hashlib.sha256(quote.encode('utf-8')).hexdigest()
    assert loc['kind']=='extracted_text' and loc['unit']=='utf16' and loc['end']-loc['start']==len(quote.encode('utf-16-le'))//2
    return loc

def main():
    p=argparse.ArgumentParser();p.add_argument('--archive',type=Path,required=True);p.add_argument('--run-dir',type=Path,required=True);a=p.parse_args()
    out=a.run_dir.resolve();out.mkdir(parents=True,exist_ok=False)
    with tarfile.open(a.archive) as archive:
        for member in archive.getmembers():
            path=PurePosixPath(member.name)
            assert not path.is_absolute() and '..' not in path.parts and (member.isdir() or member.isfile())
        archive.extractall(out,filter='data')
    base=out/'cache/b2e-library-evidence';assert base.is_dir();checked=0;pages_checked=0
    for label in ['overlap','corrupt','formats']:
        folder=base/label;owner=(folder/'owner.txt').read_text();items=metadata(folder);by_id={i['id']:i for i in items}
        pages=json.loads((folder/'pages.json').read_text(encoding='utf-8'));query='aa' if label=='overlap' else 'needle' if label=='corrupt' else 'LIBRARY_MATCH'
        snap=fingerprint(owner,query,items)
        for page in pages:
            assert page['owner']==owner and page['query']==query and page['catalog_snapshot_sha256']==snap
            assert page['scope']=='owner_library_extracted_text_page' and page['library_completeness']==page['document_completeness']=='NOT_ASSERTED'
            assert page['catalog_versions']==len(items)
            for hit in page['hits']:
                item=by_id[hit['source_receipt']['material_id']];quote_check(hit,owner,item);checked+=1
            pages_checked+=1
        if label=='overlap':
            actual=[];expected=[]
            for item in items:
                source=folder/(item['id']+'.'+item['extension']);assert digest(source)==item['sha256'] and source.stat().st_size==item['size']
                text=source.read_text(encoding='utf-8');start=0
                while (found:=text.find(query,start))>=0:
                    expected.append((item['id'],len(text[:found].encode('utf-16-le'))//2));start=found+1
            for index,page in enumerate(pages):
                assert page['status']=='SUCCEEDED' and page['visited_versions']<=1 and len(page['hits'])<=1
                for hit in page['hits']:
                    proof=hit['source_receipt'];item=by_id[proof['material_id']];loc=quote_check(hit,owner,item)
                    source=(folder/(item['id']+'.'+item['extension'])).read_text(encoding='utf-8').encode('utf-16-le')
                    assert source[loc['start']*2:loc['end']*2].decode('utf-16-le')==query
                    actual.append((item['id'],loc['start']))
                if 'next_cursor' in page:
                    cursor=json.loads(page['next_cursor']);assert cursor['snapshot_sha256']==snap
                    assert pages[index+1]['start_version_index']==int(cursor['version_index'])
                    assert pages[index+1]['start_search_offset']==int(cursor['search_offset'])
            assert actual==expected and len(actual)==6 and not pages[0]['hits'] and pages[0]['has_more']
            assert pages[-1]['exhausted_catalog'] and not pages[-1]['has_more']
        elif label=='corrupt':
            page=pages[0];assert page['status']=='FAILED' and len(page['hits'])==1 and len(page['source_errors'])==len(page['skipped_sources'])==1
            bad=by_id[page['source_errors'][0]['material_id']];assert digest(folder/(bad['id']+'.md'))!=bad['sha256']
            assert not page['source_errors'][0]['source_verified'] and not page['skipped_sources'][0]['source_verified']
            assert page['skipped_sources'][0]['reason']=='FORMAT_NOT_TEXT_READABLE'
            good=by_id[page['hits'][0]['source_receipt']['material_id']];assert digest(folder/(good['id']+'.md'))==good['sha256']
        else:
            page=pages[0];assert page['status']=='SUCCEEDED' and len(page['hits'])==4
            pdf=next(i for i in items if i['extension']=='pdf');ppt=next(i for i in items if i['extension']=='pptx')
            for item in items:assert digest(folder/(item['id']+'.'+item['extension']))==item['sha256']
            reader=PdfReader(folder/(pdf['id']+'.pdf'));assert len(reader.pages)==3
            expected_pdf=[i+1 for i,page in enumerate(reader.pages) if query in (page.extract_text() or '')];assert expected_pdf==[1,3]
            rns='http://schemas.openxmlformats.org/officeDocument/2006/relationships';pns='http://schemas.openxmlformats.org/presentationml/2006/main';ans='http://schemas.openxmlformats.org/drawingml/2006/main'
            with zipfile.ZipFile(folder/(ppt['id']+'.pptx')) as package:
                relations={node.attrib['Id']:node.attrib['Target'] for node in ET.fromstring(package.read('ppt/_rels/presentation.xml.rels'))}
                slides=ET.fromstring(package.read('ppt/presentation.xml')).findall('.//{'+pns+'}sldId')
                expected_ppt=[]
                for i,slide in enumerate(slides):
                    part='ppt/'+relations[slide.attrib['{'+rns+'}id']]
                    body=''.join(ET.fromstring(package.read(part)).itertext())
                    if query in body:expected_ppt.append(i+1)
            assert expected_ppt==[1,2]
            actual_pdf=[];actual_ppt=[]
            for hit in page['hits']:
                proof=hit['source_receipt'];where=proof['source_locations'];assert len(where)==1
                if proof['material_id']==pdf['id']:
                    assert where[0]['kind']=='pdf_physical_page';actual_pdf.append(where[0]['number'])
                else:
                    assert where[0]['kind']=='ppt_logical_slide';actual_ppt.append(where[0]['number'])
            assert actual_pdf==expected_pdf and actual_ppt==expected_ppt
            pdf_summary=next(s for s in page['scanned_sources'] if s['material_id']==pdf['id'])
            assert pdf_summary['pages_without_extractable_text']==[2]
    result=dict(status='PASS',pagesChecked=pages_checked,quotedHitsChecked=checked,archiveSha256=digest(a.archive),verifierSha256=digest(Path(__file__)),pypdfVersion=pypdf.__version__,evidence='actual Android Tool pages/source SHA/UTF16 overlap/owner/parent/version/cursor; independent PDF physical pages and PPT logical XML order; corrupt source remains FAILED')
    (out/'RESULT.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
if __name__=='__main__':main()
