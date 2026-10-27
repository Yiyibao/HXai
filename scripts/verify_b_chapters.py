"""Verify actual Android chapter snapshots and quotes against independently opened source bytes."""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import tarfile
import xml.etree.ElementTree as ET
import zipfile

from pypdf import PdfReader
from verify_b_library_evidence import quote_check


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def utf16_slice(text, start, end):
    return text.encode('utf-16-le')[start * 2:end * 2].decode('utf-16-le')


def verify_index(folder):
    item = json.loads((folder / 'source.meta.json').read_text(encoding='utf-8'))
    source = folder / ('source.' + item['extension'])
    assert digest(source) == item['sha256'] and source.stat().st_size == item['size']
    snapshot = json.loads((folder / 'persisted-index.json').read_text(encoding='utf-8'))
    pages = json.loads((folder / 'pages.json').read_text(encoding='utf-8'))
    expected_hash = snapshot.pop('index_sha256')
    encoded = json.dumps(snapshot, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    assert hashlib.sha256(encoded).hexdigest() == expected_hash
    snapshot['index_sha256'] = expected_hash
    assert snapshot['scope'] == 'detected_heading_sections_in_extracted_text'
    assert snapshot['owner'] == item['profileId'] and snapshot['material_id'] == snapshot['version_id'] == item['id']
    assert snapshot['source_sha256'] == item['sha256'] and snapshot['document_completeness'] == 'NOT_ASSERTED'
    assert snapshot['contractVersion'] == 1 and not snapshot['index_truncated']
    returned = []
    for page in pages:
        assert page['index_sha256'] == expected_hash and page['source_verified'] and page['status'] == 'SUCCEEDED'
        start = page['index_offset']
        assert page['chapters'] == snapshot['chapters'][start:start + len(page['chapters'])]
        assert page['has_more'] == (start + len(page['chapters']) < len(snapshot['chapters']))
        returned.extend(page['chapters'])
    assert returned == snapshot['chapters']
    raw_text = source.read_text(encoding='utf-8') if item['extension'] == 'md' else None
    pdf = PdfReader(source) if item['extension'] == 'pdf' else None
    ppt = []
    if item['extension'] == 'pptx':
        with zipfile.ZipFile(source) as package:
            rns = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
            pns = 'http://schemas.openxmlformats.org/presentationml/2006/main'
            ans = 'http://schemas.openxmlformats.org/drawingml/2006/main'
            rels = {node.attrib['Id']: node.attrib['Target'] for node in ET.fromstring(package.read('ppt/_rels/presentation.xml.rels'))}
            for slide in ET.fromstring(package.read('ppt/presentation.xml')).findall('.//{' + pns + '}sldId'):
                part = 'ppt/' + rels[slide.attrib['{' + rns + '}id']]
                root = ET.fromstring(package.read(part))
                ppt.append((part, root.attrib.get('show') in ['0', 'false'], ''.join((node.text or '') + '\n' for node in root.iter('{' + ans + '}t'))))
    for number, chapter in enumerate(snapshot['chapters']):
        evidence = chapter['heading_evidence']
        loc = quote_check(evidence, item['profileId'], item)
        quote = evidence['untrusted_file_data']
        assert quote == chapter['title'] and loc['start'] == chapter['start']
        assert chapter['chapter_id'] == f"heading-{chapter['start']}"
        assert chapter['structure_authority'] == 'HEADING_CANDIDATE_ONLY'
        assert chapter['end'] == (snapshot['chapters'][number + 1]['start'] if number + 1 < len(snapshot['chapters']) else snapshot['extracted_characters'])
        if raw_text is not None:
            assert utf16_slice(raw_text, loc['start'], loc['end']) == quote
        else:
            where = evidence['source_receipt']['source_locations']
            assert len(where) == 1
            where = where[0]
            if pdf is not None:
                assert where['kind'] == 'pdf_physical_page' and quote in pdf.pages[where['number'] - 1].extract_text()
            else:
                part, hidden, body = ppt[where['number'] - 1]
                assert where['kind'] == 'ppt_logical_slide' and where['part'] == part and where['hidden'] == hidden and quote in body
    if pdf is not None:
        assert len(pdf.pages) == 3 and not pdf.pages[1].extract_text().strip()
        assert snapshot['pages_without_extractable_text'] == [2]
    if ppt:
        assert ppt[0][0] == 'ppt/slides/slide2.xml' and ppt[0][1]
    return dict(label=folder.name, headings=len(snapshot['chapters']), indexSha256=expected_hash, sourceSha256=item['sha256'])


def verify_reads(folder):
    item = json.loads((folder / 'source.meta.json').read_text(encoding='utf-8'))
    source = folder / ('source.' + item['extension'])
    assert digest(source) == item['sha256'] and source.stat().st_size == item['size']
    index = json.loads((folder / 'index-page.json').read_text(encoding='utf-8'))
    snapshot = json.loads((folder / 'persisted-index.json').read_text(encoding='utf-8'))
    expected_hash = snapshot.pop('index_sha256')
    assert hashlib.sha256(json.dumps(snapshot, ensure_ascii=False, separators=(',', ':')).encode('utf-8')).hexdigest() == expected_hash
    assert index['index_sha256'] == expected_hash and index['chapters'] == snapshot['chapters']
    assert snapshot['source_sha256'] == item['sha256'] and snapshot['owner'] == item['profileId']
    chapters = {chapter['chapter_id']: chapter for chapter in index['chapters']}
    pages = json.loads((folder / 'pages.json').read_text(encoding='utf-8'))
    text = source.read_text(encoding='utf-8') if item['extension'] == 'md' else None
    pdf = PdfReader(source) if item['extension'] == 'pdf' else None
    ppt = []
    if item['extension'] == 'pptx':
        with zipfile.ZipFile(source) as package:
            rns = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
            pns = 'http://schemas.openxmlformats.org/presentationml/2006/main'
            ans = 'http://schemas.openxmlformats.org/drawingml/2006/main'
            rels = {node.attrib['Id']: node.attrib['Target'] for node in ET.fromstring(package.read('ppt/_rels/presentation.xml.rels'))}
            for slide in ET.fromstring(package.read('ppt/presentation.xml')).findall('.//{' + pns + '}sldId'):
                part = 'ppt/' + rels[slide.attrib['{' + rns + '}id']]
                root = ET.fromstring(package.read(part))
                ppt.append((part, root.attrib.get('show') in ['0', 'false'], ''.join((node.text or '') + '\n' for node in root.iter('{' + ans + '}t'))))
    checked = 0
    for page in pages:
        assert page['scope'] == 'detected_chapter_extracted_body_chunk' and page['contractVersion'] == 1
        assert page['index_sha256'] == index['index_sha256'] and page['source_sha256'] == item['sha256']
        assert page['owner'] == item['profileId'] and page['source_verified']
        assert page['chapter_completeness'] == page['document_completeness'] == 'NOT_ASSERTED'
        chapter = chapters[page['chapter_id']]
        assert page['chapter_start'] == chapter['start'] and page['chapter_end'] == chapter['end']
        assert page['chapter_span_characters'] == chapter['end'] - chapter['start']
        if page['status'] == 'NOT_AVAILABLE':
            assert not page['has_more_in_chapter'] and 'source_receipt' not in page and 'untrusted_file_data' not in page
            continue
        assert page['status'] == 'SUCCEEDED' and 'next_offset' not in page
        loc = quote_check(page, item['profileId'], item)
        quote = page['untrusted_file_data']
        assert chapter['start'] <= loc['start'] < loc['end'] <= chapter['end']
        assert loc['start'] == chapter['start'] + page['chapter_offset']
        assert page['chapter_offset'] >= page['requested_chapter_offset']
        assert len(quote.encode('utf-16-le')) // 2 <= 24000
        if page['has_more_in_chapter']:
            assert page['next_chapter_offset'] == loc['end'] - chapter['start']
        else:
            assert 'next_chapter_offset' not in page
        if text is not None:
            assert utf16_slice(text, loc['start'], loc['end']) == quote
        else:
            where = page['source_receipt']['source_locations']
            assert len(where) == 1
            where = where[0]
            if pdf is not None:
                assert where['kind'] == 'pdf_physical_page'
                # PDF has positioned glyphs, not canonical paragraph separators shared by extractors.
                actual_lines = [line for line in quote.splitlines() if line.strip()]
                source_lines = [line for line in (pdf.pages[where['number'] - 1].extract_text() or '').splitlines() if line.strip()]
                assert actual_lines == source_lines
                assert '物理页' not in quote and '无可提取' not in quote
            else:
                part, hidden, body = ppt[where['number'] - 1]
                assert where['part'] == part and where['hidden'] == hidden and quote == body
        checked += 1
    if pdf is not None:
        assert len(pdf.pages) == 4 and not pdf.pages[1].extract_text().strip()
        assert [p['source_receipt']['source_locations'][0]['number'] for p in pages if p['status'] == 'SUCCEEDED'] == [1, 3]
        assert 'Chapter 2' not in ''.join(p.get('untrusted_file_data', '') for p in pages)
    if folder.name == 'pagination':
        chapter = chapters[pages[0]['chapter_id']]
        assert ''.join(page['untrusted_file_data'] for page in pages) == utf16_slice(text, chapter['start'], chapter['end'])
        for before, after in zip(pages, pages[1:]):
            assert before['next_chapter_offset'] == after['requested_chapter_offset'] == after['chapter_offset']
            assert before['source_receipt']['locator']['end'] == after['source_receipt']['locator']['start']
        assert not pages[-1]['has_more_in_chapter'] and 'NEIGHBOR' not in ''.join(page['untrusted_file_data'] for page in pages)
    return dict(label=folder.name, quotedChunks=checked, sourceSha256=item['sha256'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    out = args.run_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    with tarfile.open(args.archive) as archive:
        for member in archive.getmembers():
            path = PurePosixPath(member.name)
            assert not path.is_absolute() and '..' not in path.parts and (member.isfile() or member.isdir())
            assert member.size <= 30 * 1024 * 1024
        archive.extractall(out, filter='data')
    base = out / 'cache/b-chapter-index-evidence'
    rows = [verify_index(base / label) for label in ['markdown', 'pdf', 'ppt']]
    read_base = out / 'cache/b-chapter-read-evidence'
    reads = [verify_reads(read_base / label) for label in ['markdown', 'pdf', 'ppt', 'pagination']] if read_base.is_dir() else []
    result = dict(status='PASS', snapshots=rows, chapterReads=reads, archiveSha256=digest(args.archive), verifierSha256=digest(Path(__file__)))
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
