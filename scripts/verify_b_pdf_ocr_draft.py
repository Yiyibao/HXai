"""Real native OCR-candidate Word export; synthetic parser input is never called real OCR.
Uses installed UNO, pypdf, Pillow and PDFium. No upload, model call, installation or daemon start.
"""
import argparse
import ctypes
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from verify_b_docx_omml import digest, configure_pdfium, render_pdf

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / 'server/document-service'
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
M = '{http://schemas.openxmlformats.org/officeDocument/2006/math}'


def check_word(path, item, source_hash):
    from PIL import Image
    import io
    assert digest(path) == item['sha256']
    proof = json.loads(item['detail'])
    assert proof['scope'] == 'pdf_ocr_editable_draft' and proof['source_sha256'] == source_hash
    assert proof['recognition_correctness'] == 'UNVERIFIED' and proof['math_verification'] == 'NOT_PERFORMED'
    assert proof['table_representation'] == 'EDITABLE_TEXT_NOT_WORD_TABLES'
    assert proof['formula_representation'] == 'UNVERIFIED_EDITABLE_LATEX_TEXT_NOT_OMML'
    with ZipFile(path) as archive:
        root = ET.fromstring(archive.read('word/document.xml'))
        assert not list(root.iter(M + 'oMath')), 'Literal OCR candidates must not pretend to be verified OMML'
        text = '\n'.join(element.text or '' for element in root.iter(W + 't'))
        images = []
        for reference in proof['reference_images']:
            raw = archive.read(reference['image_part'])
            import hashlib
            assert hashlib.sha256(raw).hexdigest() == reference['png_sha256']
            with Image.open(io.BytesIO(raw)) as image:
                image.load()
                assert image.size == (reference['width'], reference['height'])
                assert max(image.size) == 1536
                images.append((reference['source_physical_page'], reference['png_sha256']))
        assert [page for page, _ in images] == proof['selected_source_pages']
        assert len(images) == len(root.findall('.//{http://schemas.openxmlformats.org/drawingml/2006/main}blip'))
        captions = []
        previous = None
        for paragraph in root.find(W + 'body'):
            if paragraph.find('.//' + W + 'drawing') is not None:
                assert previous is not None
                captions.append(''.join(element.text or '' for element in previous.iter(W + 't')))
            previous = paragraph
        assert len(captions) == len(images) and all(captions)
    return proof, text, dict(images), captions


def worker(folder):
    from office_worker.package import inspect_package
    from office_worker.run import export, version
    from office_worker.check_output import validate_pdf
    result = {'rendererVersion': version(), 'documents': {}}
    for name in json.loads((folder / 'REQUESTED_RENDER_INPUTS.json').read_text(encoding='utf-8')):
        source = folder / (name + '.docx'); source_hash = digest(source)
        inspect_package(source, 'docx', source_hash)
        output = folder / (name + '.pdf')
        export(source, 'docx', output)
        pages = validate_pdf(output)
        assert digest(source) == source_hash
        result['documents'][name] = {'docxSha256': source_hash, 'pdfSha256': digest(output), 'pages': pages}
    (folder / 'OFFICE_RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-dir', type=Path)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--worker-child', action='store_true')
    parser.add_argument('--six-page-boundary', action='store_true')
    args = parser.parse_args(); folder = args.run_dir.resolve()
    if args.worker_child:
        worker(folder)
        return
    if os.name != 'nt' or args.source_dir is None:
        parser.error('Needs Windows LibreOffice and actual native draft evidence')
    folder.mkdir(parents=True, exist_ok=False)
    receipt = json.loads((args.source_dir / 'result.json').read_text(encoding='utf-8'))
    assert receipt['status'] == 'PASS' and receipt['realOcr'] == 'NOT_RUN' and receipt['cacheOrigin'] == 'SYNTHETIC_PROTOCOL_FIXTURE'
    names_meta = {'editable-draft': 'output'}
    files = ['source.pdf', 'editable-draft.docx', 'output.meta.json', 'result.json']
    if args.six_page_boundary:
        assert receipt['ordinaryCreateSixImagesRejectedWithoutOutput']
        files += ['chunk-1-document', 'chunk-1-manifest.json', 'chunk-2-document', 'chunk-2-manifest.json']
    else:
        names_meta['selected-draft'] = 'selected'
        files += ['selected-draft.docx', 'selected.meta.json', 'synthetic-parsed-document.json', 'synthetic-manifest.json']
    for name in files:
        shutil.copy2(args.source_dir / name, folder / name)
    (folder / 'REQUESTED_RENDER_INPUTS.json').write_text(json.dumps(list(names_meta)), encoding='utf-8')
    source_hash = digest(folder / 'source.pdf'); assert source_hash == receipt['sourceSha256']
    import pypdf
    from pypdf import PdfReader
    source_pdf = PdfReader(folder / 'source.pdf')
    assert len(source_pdf.pages) == (6 if args.six_page_boundary else 3) and all(not page.extract_text().strip() for page in source_pdf.pages)
    original = json.loads((folder / ('chunk-1-document' if args.six_page_boundary else 'synthetic-parsed-document.json')).read_text(encoding='utf-8'))
    if args.six_page_boundary:
        second = json.loads((folder / 'chunk-2-document').read_text(encoding='utf-8'))
        assert not original['complete'] and not second['complete']
        assert original['requestedRange'] == [1, 3] and second['requestedRange'] == [4, 6]
    proofs = {}; raw_texts = {}; image_maps = {}; captions = {}
    for name, meta_name in names_meta.items():
        item = json.loads((folder / (meta_name + '.meta.json')).read_text(encoding='utf-8'))
        proofs[name], raw_texts[name], image_maps[name], captions[name] = check_word(folder / (name + '.docx'), item, source_hash)
        for warning in original['warnings']:
            assert warning in raw_texts[name]
        assert proofs[name]['source_total_pages'] == len(source_pdf.pages)
    assert proofs['editable-draft']['entire_source_selected']
    assert 'x\\times 1' in raw_texts['editable-draft'] and 'p1-b1' in raw_texts['editable-draft']
    if args.six_page_boundary:
        assert proofs['editable-draft']['selected_source_pages'] == list(range(1, 7))
        assert len(proofs['editable-draft']['reference_images']) == 6
    else:
        assert not proofs['selected-draft']['entire_source_selected']
        assert proofs['selected-draft']['selected_source_pages'] == [2, 3]
        assert all(image_maps['editable-draft'][page] == value for page, value in image_maps['selected-draft'].items())
        assert 'p1-b1' not in raw_texts['selected-draft']
    office = Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'LibreOffice/program'
    env = dict(os.environ); env['PATH'] = str(office) + os.pathsep + env.get('PATH', '')
    env['PYTHONPATH'] = os.pathsep.join([str(SERVICE), str(Path(pypdf.__file__).parents[1])])
    command = [str(office / 'python.exe'), str(Path(__file__).resolve()), '--worker-child', '--run-dir', str(folder)]
    started = time.monotonic(); child = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, timeout=240)
    (folder / 'real-office.log').write_bytes(child.stdout + b'\n' + child.stderr)
    (folder / 'WORKER_RUN.json').write_text(json.dumps({'cwd': str(ROOT), 'command': command, 'exitCode': child.returncode, 'seconds': round(time.monotonic() - started, 3)}, indent=2), encoding='utf-8')
    child.check_returncode()
    texts = {}; caption_layout = {}
    for name in names_meta:
        pdf_pages = PdfReader(folder / (name + '.pdf')).pages
        texts[name] = [page.extract_text() for page in pdf_pages]
        caption_pages = []
        for caption in captions[name]:
            matches = [index + 1 for index, text in enumerate(texts[name]) if ''.join(caption.split()) in ''.join(text.split())]
            assert len(matches) == 1
            caption_pages.append(matches[0])
        image_pages = []
        for index, page in enumerate(pdf_pages, 1):
            objects = page['/Resources'].get('/XObject')
            objects = objects.get_object() if objects is not None else {}
            for operands, operator in page.get_contents().operations:
                if operator == b'Do' and objects[operands[0]]['/Subtype'] == '/Image':
                    image_pages.append(index)
        assert caption_pages == image_pages, 'Original-page reference captions must stay with the actual image draws, not merely shared image resources'
        caption_layout[name] = {'captionPages': caption_pages, 'actualImageDrawPages': image_pages}
        joined = '\n'.join(texts[name])
        assert ''.join(original['warnings'][0].split()) in ''.join(joined.split())
        assert 'Header' in joined and 'Value' in joined
    assert 'Recognized candidate lesson A' in '\n'.join(texts['editable-draft'])
    if not args.six_page_boundary:
        assert 'Recognized candidate lesson A' not in '\n'.join(texts['selected-draft'])
    with os.add_dll_directory(str(office)):
        pdfium = ctypes.CDLL(str(office / 'pdfiumlo.dll')); configure_pdfium(pdfium); pdfium.FPDF_InitLibrary()
        try:
            pages = {name: render_pdf(pdfium, folder / (name + '.pdf'), folder, name) for name in ['source', *names_meta]}
        finally:
            pdfium.FPDF_DestroyLibrary()
    result = {'status': 'PASS', 'scope': 'actual_native_editable_ocr_candidate_word_and_source_images', 'realOcr': 'NOT_RUN',
              'parserInput': 'SYNTHETIC_PROTOCOL_FIXTURE', 'sourcePdfSha256': source_hash, 'wordProofs': proofs,
              'office': json.loads((folder / 'OFFICE_RESULT.json').read_text(encoding='utf-8')), 'independentPdfiumPages': pages,
              'textsPerPage': texts, 'referenceCaptionLayout': caption_layout, 'layout': 'REFERENCE_CAPTION_IMAGE_SAME_PAGE_VERIFIED_ONLY', 'ocrAccuracy': 'NOT_EVALUATED', 'crossSoftware': 'NOT_RUN',
              'scriptSha256': digest(Path(__file__)), 'artifacts': {p.name: digest(p) for p in folder.iterdir() if p.is_file()}}
    (folder / 'RESULT.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'status': result['status'], 'realOcr': result['realOcr'], 'pages': pages, 'sourceSha256': source_hash}, indent=2))


if __name__ == '__main__':
    main()
