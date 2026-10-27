"""B02: consume native Word versions, export with real UNO, independently read/render PDF.
Local synthetic inputs only; no install, upload, production service, or Microsoft fidelity claim.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from verify_b_docx_omml import configure_pdfium, render_pdf

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / 'server/document-service'
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
M = '{http://schemas.openxmlformats.org/officeDocument/2006/math}'
DOCUMENTS = ('source-A', 'edited-A', 'source-B', 'edited-B', 'created-notes')


def digest(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def package(path):
    with ZipFile(path) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


def structure(node):
    return node.tag, tuple(sorted(node.attrib.items())), node.text or '', node.tail or '', tuple(structure(child) for child in node)


def independently_check_versions(folder, receipt):
    checks = []
    for document in receipt['documents']:
        label = document['label']
        for prefix, key in [('source', 'sourceSha256'), ('paragraph', 'parentSha256'), ('edited', 'artifactSha256')]:
            assert digest(folder / f'{prefix}-{label}.docx') == document[key], 'Native version hash mismatch'
        before = package(folder / f'source-{label}.docx')
        after = package(folder / f'edited-{label}.docx')
        assert before.keys() == after.keys()
        for name in before:
            if name != 'word/document.xml':
                assert before[name] == after[name], f'Changed unrelated package part: {name}'
        source = ET.fromstring(before['word/document.xml']); edited = ET.fromstring(after['word/document.xml'])
        index = document['paragraphSpec']['index'] - 1
        old_paragraph = list(source.iter(W + 'p'))[index]
        new_paragraph = list(edited.iter(W + 'p'))[index]
        assert ''.join(node.text or '' for node in old_paragraph.iter(W + 't')) == 'TARGET old phrase END TAIL'
        assert ''.join(node.text or '' for node in new_paragraph.iter(W + 't')) == 'TARGET new precise phrase END TAIL'
        old_runs = old_paragraph.findall(W + 'r'); new_runs = new_paragraph.findall(W + 'r')
        assert new_runs[1].find(W + 'rPr/' + W + 'i') is not None
        assert new_runs[1].find(W + 't').text == 'new precise phrase'
        assert new_runs[2].find(W + 'rPr/' + W + 'b') is not None
        for old, new in zip(old_runs[1:3], new_runs[1:3], strict=True):
            old.find(W + 't').text = new.find(W + 't').text = 'masked selected text'
        equation_index = document['equationSpec']['index'] - 1
        old_equation = list(source.iter(M + 'oMath'))[equation_index]
        new_equation = list(edited.iter(M + 'oMath'))[equation_index]
        assert new_equation.find(M + 'f/' + M + 'num/' + M + 'sSubSup') is not None
        assert new_equation.find(M + 'f/' + M + 'den/' + M + 'rad') is not None
        assert ''.join(node.text or '' for node in new_equation.iter(M + 't')) == 'xn2a+b'
        for node in (old_equation, new_equation):
            for child in list(node):
                node.remove(child)
        assert structure(source) == structure(edited), 'Unselected main-document XML changed'
        quote_receipt = document['sourceQuote']['source_receipt']
        assert quote_receipt['material_id'] == document['sourceId'] and quote_receipt['owner'] == receipt['owner']
        checks.append({'label': label, 'originalSha256': document['sourceSha256'], 'paragraphVersionSha256': document['parentSha256'],
                       'artifactSha256': document['artifactSha256'], 'unrelatedPartsByteIdentical': True, 'unselectedMainXmlEqual': True,
                       'realOmmlFractionSubSupRadical': True, 'replacementRetainsFirstRunItalicAndAdjacentBold': True})
    return checks


def worker(folder):
    from office_worker.package import inspect_package
    from office_worker.run import export, version
    from office_worker.check_output import validate_pdf
    result = {'renderer': 'LibreOffice', 'version': version(), 'documents': {}}
    for name in DOCUMENTS:
        source = folder / (name + '.docx'); source_hash = digest(source)
        fonts = inspect_package(source, 'docx', source_hash)
        target = folder / (name + '.pdf')
        export(source, 'docx', target)
        pages = validate_pdf(target)
        assert digest(source) == source_hash, 'Office changed immutable native input'
        result['documents'][name] = {'sourceSha256': source_hash, 'pdfSha256': digest(target), 'pages': pages, 'requestedFonts': sorted(fonts)}
    (folder / 'OFFICE_RESULT.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--source-dir', type=Path)
    parser.add_argument('--office', type=Path, default=Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'LibreOffice/program')
    parser.add_argument('--worker-child', action='store_true')
    args = parser.parse_args(); folder = args.run_dir.resolve()
    if args.worker_child:
        worker(folder)
        return
    if os.name != 'nt' or args.source_dir is None:
        parser.error('Requires Windows, installed LibreOffice and native synthetic Word evidence')
    folder.mkdir(parents=True, exist_ok=False)
    receipt = json.loads((args.source_dir / 'result.json').read_text(encoding='utf-8'))
    created = json.loads((args.source_dir / 'created-result.json').read_text(encoding='utf-8'))
    assert receipt['status'] == 'PASS' and len(receipt['documents']) == 2
    for name in DOCUMENTS + ('paragraph-A', 'paragraph-B'):
        shutil.copy2(args.source_dir / (name + '.docx'), folder / (name + '.docx'))
    assert digest(folder / 'created-notes.docx') == created['sha256']
    checks = independently_check_versions(folder, receipt)
    import pypdf
    from pypdf import PdfReader
    office = args.office.resolve(); env = dict(os.environ)
    env['PATH'] = str(office) + os.pathsep + env.get('PATH', '')
    env['PYTHONPATH'] = os.pathsep.join([str(SERVICE), str(Path(pypdf.__file__).parents[1])])
    command = [str(office / 'python.exe'), str(Path(__file__).resolve()), '--worker-child', '--run-dir', str(folder)]
    started = time.monotonic(); child = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, timeout=240)
    (folder / 'real-office.log').write_bytes(child.stdout + b'\n' + child.stderr)
    (folder / 'WORKER_RUN.json').write_text(json.dumps({'cwd': str(ROOT), 'command': command, 'exitCode': child.returncode, 'seconds': round(time.monotonic() - started, 3)}, indent=2), encoding='utf-8')
    child.check_returncode()
    pages = {}; locations = {}; texts = {}; images = {}
    for name in DOCUMENTS:
        pdf = PdfReader(folder / (name + '.pdf'))
        texts[name] = [page.extract_text() for page in pdf.pages]
        # XObject resources can be shared by pages without being painted; inspect PNGs separately.
        images[name] = [len(page.images) for page in pdf.pages]
        if name != 'created-notes':
            label = name[-1]; joined = '\n'.join(texts[name])
            for expected in (f'Lecture {label} header preserved', f'Footer {label} preserved', f'Lecture {label} first-page preserved body', f'Lecture {label} last-page unchanged theorem', 'Header', 'Value', f'Data {label}'):
                assert expected in joined, f'Actual {name} PDF lost {expected}'
            assert len(pdf.pages) >= 3 and sum(images[name]) >= 1
            expected_edit = 'TARGET new precise phrase END' if name.startswith('edited') else 'TARGET old phrase END'
            assert expected_edit in joined
            locations[name] = {
                'basis': 'exported_pdf_physical_page_not_docx_paragraph_index', 'pdfSha256': digest(folder / (name + '.pdf')),
                'changedAnchorPages': [i + 1 for i, text in enumerate(texts[name]) if expected_edit in text],
                'representativeFirstPages': [i + 1 for i, text in enumerate(texts[name]) if f'Lecture {label} first-page preserved body' in text],
                'representativeLastPages': [i + 1 for i, text in enumerate(texts[name]) if f'Lecture {label} last-page unchanged theorem' in text],
                'cachedTocTextVisible': 'Cached TOC chapter 1' in joined,
            }
        else:
            joined = '\n'.join(texts[name]); normalized = ''.join(joined.split())
            for document in receipt['documents']:
                assert f'Lecture {document["label"]} last-page unchanged theorem' in joined
                assert document['sourceId'] in normalized and document['sourceSha256'] in normalized
    with os.add_dll_directory(str(office)):
        pdfium = ctypes.CDLL(str(office / 'pdfiumlo.dll')); configure_pdfium(pdfium); pdfium.FPDF_InitLibrary()
        try:
            for name in DOCUMENTS:
                pages[name] = render_pdf(pdfium, folder / (name + '.pdf'), folder, name)
                assert pages[name] == len(texts[name])
        finally:
            pdfium.FPDF_DestroyLibrary()
    for label in ('A', 'B'):
        assert locations[f'source-{label}']['cachedTocTextVisible'] == locations[f'edited-{label}']['cachedTocTextVisible']
    result = {'status': 'PASS', 'nativeVersionChecks': checks, 'realOffice': json.loads((folder / 'OFFICE_RESULT.json').read_text(encoding='utf-8')),
              'independentPdfiumPages': pages, 'renderedLocations': locations, 'imageResourcesPerPage': images, 'textsPerPage': texts,
              'tocXmlPreserved': True, 'tocRecomputed': 'NOT_CLAIMED', 'chatModelStory': 'NOT_RUN', 'crossSoftware': 'NOT_RUN',
              'fullLayoutFidelity': 'NOT_VERIFIED', 'containerIsolation': 'NOT_RUN', 'pdfiumSha256': digest(office / 'pdfiumlo.dll'),
              'sources': {str(p.relative_to(ROOT)): digest(p) for p in [Path(__file__).resolve(), Path(__file__).with_name('verify_b_docx_omml.py')] + sorted((SERVICE / 'office_worker').glob('*.py'))},
              'artifacts': {p.name: digest(p) for p in folder.iterdir() if p.is_file()}}
    (folder / 'RESULT.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'status': result['status'], 'pages': pages, 'locations': locations, 'imageResourcesPerPage': images}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
