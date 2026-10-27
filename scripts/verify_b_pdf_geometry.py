"""Independently verify actual native B03 page assembly, including raw PDF geometry."""
import argparse
import ctypes
import json
import os
from pathlib import Path
import shutil
from verify_b_docx_omml import digest, configure_pdfium, render_pdf


def geometry(page):
    return {'mediaBox': [float(value) for value in page.mediabox], 'cropBox': [float(value) for value in page.cropbox],
            'rotation': page.rotation, 'userUnit': float(page.get('/UserUnit', 1))}


def main():
    from pypdf import PdfReader
    from PIL import Image
    parser = argparse.ArgumentParser(); parser.add_argument('--source-dir', type=Path, required=True); parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args(); folder = args.run_dir.resolve(); folder.mkdir(parents=True, exist_ok=False)
    native = json.loads((args.source_dir / 'result.json').read_text(encoding='utf-8')); assert native['status'] == 'PASS'
    for name in ('source.pdf', 'output.pdf', 'source.meta.json', 'output.meta.json', 'result.json'): shutil.copy2(args.source_dir / name, folder / name)
    source_meta = json.loads((folder / 'source.meta.json').read_text(encoding='utf-8')); output_meta = json.loads((folder / 'output.meta.json').read_text(encoding='utf-8'))
    assert source_meta['profileId'] == output_meta['profileId'] and output_meta['sourceId'] == source_meta['id']
    assert digest(folder / 'source.pdf') == source_meta['sha256'] == native['sourceSha256']
    assert digest(folder / 'output.pdf') == output_meta['sha256'] == native['artifactSha256']
    detail = json.loads(output_meta['detail']); assert detail['contractVersion'] == 1 and detail['scope'] == 'pdf_original_page_assembly'
    source = PdfReader(folder / 'source.pdf'); output = PdfReader(folder / 'output.pdf'); selection = native['sourcePages']
    assert selection == [4, 1, 3, 2, 4] and len(source.pages) == 4 and len(output.pages) == len(selection)
    assert [page['source_physical_page'] for page in detail['pages']] == selection
    assert all(page['source_id'] == source_meta['id'] and page['source_sha256'] == source_meta['sha256'] for page in detail['pages'])
    assert geometry(source.pages[0]) == {'mediaBox': [-20.0, -30.0, 380.0, 570.0], 'cropBox': [10.0, 20.0, 330.0, 500.0], 'rotation': 90, 'userUnit': 1.25}
    assert geometry(source.pages[1])['userUnit'] == 2 and geometry(source.pages[1])['rotation'] == 270
    assert not source.pages[2].extract_text().strip() and not source.pages[3].extract_text().strip()
    comparisons = []
    for index, original in enumerate(selection):
        left, right = source.pages[original - 1], output.pages[index]
        assert geometry(left) == geometry(right), 'Original PDF page boxes/rotation/UserUnit changed'
        assert left.extract_text() == right.extract_text(), 'Original page text or physical page mapping changed'
        comparisons.append({'outputPhysicalPage': index + 1, 'sourcePhysicalPage': original, 'geometry': geometry(left), 'textUnchanged': True})
    office = Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'LibreOffice/program'
    with os.add_dll_directory(str(office)):
        library = ctypes.CDLL(str(office / 'pdfiumlo.dll')); configure_pdfium(library); library.FPDF_InitLibrary()
        try: counts = {name: render_pdf(library, folder / (name + '.pdf'), folder, name) for name in ('source', 'output')}
        finally: library.FPDF_DestroyLibrary()
    for index, original in enumerate(selection, 1):
        left = folder / f'source-page-{original}.png'; right = folder / f'output-page-{index}.png'
        assert digest(left) == digest(right), 'Original whole-page pixels changed'
        comparisons[index - 1]['pixelSha256'] = digest(right)
    with Image.open(folder / 'source-page-3.png') as blank:
        assert blank.convert('RGB').getextrema() == ((255, 255), (255, 255), (255, 255))
    raster = source.pages[3]; objects = raster['/Resources']['/XObject']
    assert any(operator == b'Do' and objects[operands[0]]['/Subtype'] == '/Image' for operands, operator in raster.get_contents().operations)
    result = {'status': 'PASS', 'scope': 'native_original_page_graft_with_crop_rotation_userunit_blank_and_raster', 'pages': counts, 'pageComparisons': comparisons,
              'ocr': 'NOT_PERFORMED', 'documentLevelAnnotationsFormsSignatures': 'NOT_VERIFIED', 'scriptSha256': digest(Path(__file__)),
              'artifacts': {path.name: digest(path) for path in folder.iterdir() if path.is_file()}}
    (folder / 'RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8'); print(json.dumps({'status': 'PASS', 'pages': counts, 'pageComparisons': comparisons}, indent=2))


if __name__ == '__main__': main()
