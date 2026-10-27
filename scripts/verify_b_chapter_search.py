"""Reopen actual Android search sources and check complete literal-hit sequences independently."""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import tarfile
import xml.etree.ElementTree as ET
import zipfile

from pypdf import PdfReader
from verify_b_library_evidence import quote_check
from verify_b_chapters import utf16_slice


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def occurrences(text, query):
    cursor = 0
    while True:
        found = text.find(query, cursor)
        if found < 0:
            return
        yield len(text[:found].encode('utf-16-le')) // 2
        cursor = found + 1


def verify(folder):
    item = json.loads((folder / 'source.meta.json').read_text(encoding='utf-8'))
    source = folder / ('source.' + item['extension'])
    assert digest(source) == item['sha256'] and source.stat().st_size == item['size']
    snapshot = json.loads((folder / 'persisted-index.json').read_text(encoding='utf-8'))
    index_hash = snapshot.pop('index_sha256')
    assert hashlib.sha256(json.dumps(snapshot, ensure_ascii=False, separators=(',', ':')).encode('utf-8')).hexdigest() == index_hash
    chapters = {chapter['chapter_id']: chapter for chapter in snapshot['chapters']}
    raw = source.read_text(encoding='utf-8') if item['extension'] == 'md' else None
    physical = []
    if item['extension'] == 'pdf':
        pdf = PdfReader(source)
        physical = [(page.extract_text() or '', None, None) for page in pdf.pages]
        assert len(physical) == 4 and not physical[1][0].strip()
    elif item['extension'] == 'pptx':
        with zipfile.ZipFile(source) as package:
            rns = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
            pns = 'http://schemas.openxmlformats.org/presentationml/2006/main'
            ans = 'http://schemas.openxmlformats.org/drawingml/2006/main'
            rels = {node.attrib['Id']: node.attrib['Target'] for node in ET.fromstring(package.read('ppt/_rels/presentation.xml.rels'))}
            for slide in ET.fromstring(package.read('ppt/presentation.xml')).findall('.//{' + pns + '}sldId'):
                part = 'ppt/' + rels[slide.attrib['{' + rns + '}id']]
                node = ET.fromstring(package.read(part))
                physical.append((''.join((x.text or '') + '\n' for x in node.iter('{' + ans + '}t')), part, node.attrib.get('show') in ['0', 'false']))
        assert physical[0][1:] == ('ppt/slides/slide2.xml', True)
    checked = 0
    pages = json.loads((folder / 'searches.json').read_text(encoding='utf-8'))
    for result in pages:
        assert result['status'] == 'SUCCEEDED' and result['scope'] == 'detected_chapter_extracted_body_suffix'
        assert result['source_verified'] and result['index_sha256'] == index_hash
        assert result['source_sha256'] == item['sha256'] and result['owner'] == item['profileId']
        assert result['material_id'] == result['version_id'] == item['id']
        assert result['chapter_completeness'] == result['document_completeness'] == 'NOT_ASSERTED'
        chapter = chapters[result['chapter_id']]
        assert result['chapter_start'] == chapter['start'] and result['chapter_end'] == chapter['end']
        query = result['query']
        global_offsets = []
        page_numbers = []
        for hit in result['hits']:
            loc = quote_check(hit, item['profileId'], item)
            assert 'next_offset' not in hit and hit['untrusted_file_data'] == query
            assert chapter['start'] <= loc['start'] < loc['end'] <= chapter['end']
            assert hit['chapter_offset'] + chapter['start'] == loc['start']
            assert hit['chapter_offset'] >= result['offset']
            if raw is not None:
                assert utf16_slice(raw, loc['start'], loc['end']) == query
            else:
                where = hit['source_receipt']['source_locations']
                assert len(where) == 1
                where = where[0]
                body, part, hidden = physical[where['number'] - 1]
                assert query in body
                if part is not None:
                    assert where['part'] == part and where['hidden'] == hidden
                page_numbers.append(where['number'])
            global_offsets.append(loc['start'])
            checked += 1
        assert global_offsets == sorted(set(global_offsets))
        if raw is not None:
            suffix = utf16_slice(raw, chapter['start'] + result['offset'], chapter['end'])
            expected = [chapter['start'] + result['offset'] + n for n in occurrences(suffix, query)]
            assert global_offsets == expected[:len(global_offsets)]
            if result['has_more_in_chapter']:
                assert len(expected) > len(global_offsets)
                assert result['next_chapter_search_offset'] == expected[len(global_offsets)] - chapter['start']
            else:
                assert global_offsets == expected and 'next_chapter_search_offset' not in result
        else:
            assert result['offset'] == 0 and not result['has_more_in_chapter']
            allowed = [1, 3] if item['extension'] == 'pdf' else [1]
            expected_numbers = [n for n in allowed for _ in occurrences(physical[n - 1][0], query)]
            assert page_numbers == expected_numbers
    return dict(label=folder.name, resultPages=len(pages), verifiedHits=checked, sourceSha256=item['sha256'], indexSha256=index_hash)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=False)
    with tarfile.open(args.archive) as archive:
        for member in archive.getmembers():
            path = PurePosixPath(member.name)
            assert not path.is_absolute() and '..' not in path.parts and (member.isfile() or member.isdir())
            assert member.size <= 30 * 1024 * 1024
        archive.extractall(args.run_dir, filter='data')
    base = args.run_dir / 'cache/b-chapter-search-evidence'
    rows = [verify(base / label) for label in ['markdown', 'pdf', 'ppt']]
    result = dict(status='PASS', sources=rows, archiveSha256=digest(args.archive), verifierSha256=digest(Path(__file__)))
    (args.run_dir / 'RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
