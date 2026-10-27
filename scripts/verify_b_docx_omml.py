"""Local synthetic DOCX acceptance: real UNO export, independent pypdf/PDFium readback.
Requires already-installed Windows LibreOffice, Pillow and pypdf; never installs or uploads.
Native input comes from DocxEquationRuntimeTest, not a fabricated Office result.
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

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / 'server/document-service'


def digest(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def worker(folder):
    from office_worker.package import inspect_package
    from office_worker.run import export, version
    from office_worker.check_output import validate_pdf
    result = {'renderer': 'LibreOffice', 'rendererVersion': version(), 'documents': {}}
    for name in ('source', 'edited'):
        source = folder / (name + '.docx')
        source_hash = digest(source)
        fonts = inspect_package(source, 'docx', source_hash)
        target = folder / (name + '.pdf')
        export(source, 'docx', target)
        pages = validate_pdf(target)
        assert digest(source) == source_hash, 'Source changed during real Office export'
        result['documents'][name] = {'sourceSha256': source_hash, 'pdfSha256': digest(target), 'pages': pages, 'requestedFonts': sorted(fonts)}
    (folder / 'OFFICE_RESULT.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')


def render_pdf(pdfium, pdf, folder, label):
    from PIL import Image
    document = pdfium.FPDF_LoadDocument(os.fsencode(pdf), None)
    if not document:
        raise RuntimeError('Independent PDFium could not open actual PDF')
    try:
        pages = pdfium.FPDF_GetPageCount(document)
        assert 1 <= pages <= 20
        for index in range(pages):
            page = pdfium.FPDF_LoadPage(document, index)
            if not page:
                raise RuntimeError('Independent PDFium could not load actual page')
            bitmap = None
            try:
                width = 1000
                height = round(width * pdfium.FPDF_GetPageHeight(page) / pdfium.FPDF_GetPageWidth(page))
                assert 1 <= height <= 3000
                bitmap = pdfium.FPDFBitmap_CreateEx(width, height, 4, None, 0)  # BGRA
                if not bitmap:
                    raise RuntimeError('PDFium bitmap allocation failed')
                pdfium.FPDFBitmap_FillRect(bitmap, 0, 0, width, height, 0xFFFFFFFF)
                pdfium.FPDF_RenderPageBitmap(bitmap, page, 0, 0, width, height, 0, 0)
                stride = pdfium.FPDFBitmap_GetStride(bitmap)
                data = ctypes.string_at(pdfium.FPDFBitmap_GetBuffer(bitmap), stride * height)
                image = Image.frombytes('RGBA', (width, height), data, 'raw', 'BGRA', stride)
                image.save(folder / f'{label}-page-{index + 1}.png')
            finally:
                if bitmap:
                    pdfium.FPDFBitmap_Destroy(bitmap)
                pdfium.FPDF_ClosePage(page)
        return pages
    finally:
        pdfium.FPDF_CloseDocument(document)


def configure_pdfium(library):
    pointer = ctypes.c_void_p
    declarations = {
        'FPDF_InitLibrary': (None, []), 'FPDF_DestroyLibrary': (None, []),
        'FPDF_LoadDocument': (pointer, [ctypes.c_char_p, ctypes.c_char_p]),
        'FPDF_CloseDocument': (None, [pointer]), 'FPDF_GetPageCount': (ctypes.c_int, [pointer]),
        'FPDF_LoadPage': (pointer, [pointer, ctypes.c_int]), 'FPDF_ClosePage': (None, [pointer]),
        'FPDF_GetPageWidth': (ctypes.c_double, [pointer]), 'FPDF_GetPageHeight': (ctypes.c_double, [pointer]),
        'FPDFBitmap_CreateEx': (pointer, [ctypes.c_int, ctypes.c_int, ctypes.c_int, pointer, ctypes.c_int]),
        'FPDFBitmap_FillRect': (None, [pointer, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint32]),
        'FPDF_RenderPageBitmap': (None, [pointer, pointer] + [ctypes.c_int] * 6),
        'FPDFBitmap_GetBuffer': (pointer, [pointer]), 'FPDFBitmap_GetStride': (ctypes.c_int, [pointer]),
        'FPDFBitmap_Destroy': (None, [pointer]),
    }
    for name, (result, args) in declarations.items():
        function = getattr(library, name)
        function.restype = result
        function.argtypes = args


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--source-dir', type=Path)
    parser.add_argument('--office', type=Path, default=Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'LibreOffice/program')
    parser.add_argument('--worker-child', action='store_true')
    args = parser.parse_args()
    folder = args.run_dir.resolve()
    if args.worker_child:
        worker(folder)
        return
    if os.name != 'nt' or args.source_dir is None:
        parser.error('This local proof needs Windows and the actual native synthetic source-dir')
    folder.mkdir(parents=True, exist_ok=False)
    import pypdf
    from pypdf import PdfReader
    receipt = json.loads((args.source_dir / 'result.json').read_text(encoding='utf-8'))
    assert receipt['status'] == 'PASS'
    for name, key in [('source', 'sourceSha256'), ('edited', 'artifactSha256')]:
        source = args.source_dir / (name + '.docx')
        assert digest(source) == receipt[key], 'Native receipt does not bind input bytes'
        shutil.copy2(source, folder / source.name)
    env = dict(os.environ)
    office = args.office.resolve()
    env['PATH'] = str(office) + os.pathsep + env.get('PATH', '')
    env['PYTHONPATH'] = os.pathsep.join([str(SERVICE), str(Path(pypdf.__file__).parents[1])])
    command = [str(office / 'python.exe'), str(Path(__file__).resolve()), '--worker-child', '--run-dir', str(folder)]
    started = time.monotonic()
    child = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, timeout=180)
    (folder / 'real-office.log').write_bytes(child.stdout + b'\n' + child.stderr)
    (folder / 'WORKER_RUN.json').write_text(json.dumps({'cwd': str(ROOT), 'command': command, 'exitCode': child.returncode, 'seconds': round(time.monotonic() - started, 3)}, indent=2), encoding='utf-8')
    child.check_returncode()
    texts = {name: '\n'.join(page.extract_text() for page in PdfReader(folder / (name + '.pdf')).pages) for name in ('source', 'edited')}
    for name, text in texts.items():
        for expected in ('Header preserved', 'Footer preserved', 'Before equation', 'after equation', 'Header', 'Value', 'Data'):
            assert expected in text, f'Actual {name} PDF lost {expected}'
    normalized = ''.join(texts['edited'].split()).replace('\u2212', '-')
    assert 'x+1' in normalized and 'y-1' in normalized and texts['source'] != texts['edited']
    with os.add_dll_directory(str(office)):
        pdfium = ctypes.CDLL(str(office / 'pdfiumlo.dll'))
        configure_pdfium(pdfium)
        pdfium.FPDF_InitLibrary()
        try:
            pages = {name: render_pdf(pdfium, folder / (name + '.pdf'), folder, name) for name in ('source', 'edited')}
        finally:
            pdfium.FPDF_DestroyLibrary()
    assert pages['source'] == pages['edited'], 'Representative document page count changed'
    report = {'status': 'PASS', 'realOffice': json.loads((folder / 'OFFICE_RESULT.json').read_text(encoding='utf-8')), 'independentPdfiumPages': pages,
              'texts': texts, 'crossSoftware': 'NOT_RUN', 'fullLayoutFidelity': 'NOT_VERIFIED', 'containerIsolation': 'NOT_RUN',
              'sources': {str(p.relative_to(ROOT)): digest(p) for p in [Path(__file__).resolve()] + sorted((SERVICE / 'office_worker').glob('*.py'))},
              'pdfiumSha256': digest(office / 'pdfiumlo.dll'), 'artifacts': {p.name: digest(p) for p in folder.iterdir() if p.is_file()}}
    (folder / 'RESULT.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
