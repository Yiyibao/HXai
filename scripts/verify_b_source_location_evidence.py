"""Verify actual Android location-read output against archived synthetic PDF/PPT bytes."""
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    out = args.run_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    with tarfile.open(args.archive) as archive:
        for entry in archive.getmembers():
            path = PurePosixPath(entry.name)
            assert not path.is_absolute() and '..' not in path.parts
            assert entry.isfile() or entry.isdir()
        archive.extractall(out, filter='data')
    base = out / 'cache/b2f-location-evidence'
    checked = 0
    unavailable = 0
    for label in ['pdf', 'ppt', 'truncated']:
        folder = base / label
        item = json.loads((folder / 'source.meta.json').read_text(encoding='utf-8'))
        source = folder / ('source.' + item['extension'])
        assert source.stat().st_size == item['size'] and digest(source) == item['sha256']
        pages = json.loads((folder / 'pages.json').read_text(encoding='utf-8'))
        for page in pages:
            assert page['contractVersion'] == 1 and page['scope'] == 'source_location_extracted_body'
            assert page['document_completeness'] == 'NOT_ASSERTED' and page['source_verified']
            assert 'next_offset' not in page
            if page['status'] == 'NOT_AVAILABLE':
                assert 'source_receipt' not in page and 'untrusted_file_data' not in page
                assert page['reason'] == 'NO_EXTRACTED_BODY_FOR_LOCATION'
                assert page['source_sha256'] == item['sha256'] and page['owner'] == item['profileId']
                unavailable += 1
                continue
            assert page['status'] == 'SUCCEEDED'
            loc = quote_check(page, item['profileId'], item)
            where = page['source_receipt']['source_locations']
            assert len(where) == 1 and where[0]['kind'] == page['location_kind']
            assert where[0]['number'] == page['location_number']
            assert where[0]['start'] == loc['start'] and where[0]['end'] == loc['end']
            size = len(page['untrusted_file_data'].encode('utf-16-le')) // 2
            end = page['location_offset'] + size
            assert 0 <= page['location_offset'] < end <= page['location_extracted_characters']
            assert page['has_more_in_location'] == (end < page['location_extracted_characters'])
            assert page.get('next_location_offset') == (end if page['has_more_in_location'] else None)
            checked += 1
        if label == 'pdf':
            doc = PdfReader(source)
            assert len(doc.pages) == 3 and not (doc.pages[1].extract_text() or '').strip()
            for page in [pages[0], pages[2]]:
                expected = (doc.pages[page['location_number'] - 1].extract_text() or '').strip()
                assert page['untrusted_file_data'].strip() == expected
            assert pages[1]['location_number'] == 2 and pages[1]['pages_without_extractable_text'] == [2]
        else:
            with zipfile.ZipFile(source) as package:
                rel_ns = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
                p_ns = 'http://schemas.openxmlformats.org/presentationml/2006/main'
                a_ns = 'http://schemas.openxmlformats.org/drawingml/2006/main'
                rels = {node.attrib['Id']: node.attrib['Target'] for node in ET.fromstring(package.read('ppt/_rels/presentation.xml.rels'))}
                slides = ET.fromstring(package.read('ppt/presentation.xml')).findall('.//{' + p_ns + '}sldId')
                bodies = []
                for slide in slides:
                    part = 'ppt/' + rels[slide.attrib['{' + rel_ns + '}id']]
                    xml = ET.fromstring(package.read(part))
                    body = ''.join((node.text or '') + '\n' for node in xml.iter('{' + a_ns + '}t'))
                    bodies.append((part, xml.attrib.get('show') in ['0', 'false'], body))
                for page in pages:
                    if page['status'] != 'SUCCEEDED':
                        continue
                    part, hidden, body = bodies[page['location_number'] - 1]
                    where = page['source_receipt']['source_locations'][0]
                    assert where['part'] == part and where['hidden'] == hidden
                    expected = body.encode('utf-16-le')[page['location_offset'] * 2:]
                    size = len(page['untrusted_file_data'].encode('utf-16-le'))
                    assert page['untrusted_file_data'] == expected[:size].decode('utf-16-le')
                if label == 'ppt':
                    first = [p for p in pages if p['location_number'] == 1]
                    assert ''.join(p['untrusted_file_data'] for p in first) == bodies[0][2]
                    assert bodies[0][0] == 'ppt/slides/slide2.xml' and bodies[0][1]
                    for before, after in zip(first, first[1:]):
                        assert before['next_location_offset'] == after['location_offset']
                        assert before['source_receipt']['locator']['end'] == after['source_receipt']['locator']['start']
                else:
                    assert pages[-1]['status'] == 'NOT_AVAILABLE' and pages[-1]['location_number'] == 2
                    assert pages[1]['location_extracted_characters'] < len(bodies[0][2])
    result = dict(status='PASS', quotedRangesChecked=checked, unavailableLocationsChecked=unavailable,
                  archiveSha256=digest(args.archive), verifierSha256=digest(Path(__file__)),
                  evidence='Actual Android tool output vs independent PDF physical pages/PPT relationship order, hidden metadata, UTF16 chunks, source and quote SHA; no fake blank/truncated body')
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
