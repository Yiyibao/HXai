"""Run installed Windows OCR on a real local PDF, preserving source/engine evidence.
No downloads, network, credentials, model calls, truth input or automatic confirmation.
The resulting parser-v1 document is an untrusted candidate, not a math/layout certificate.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
MAX_SOURCE = 30 * 1024 * 1024


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_new(path, value):
    with Path(path).open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def normalize(raw, source_hash, total, start, end, images):
    """Adapt real recognized lines only; never invent equation/LaTeX or confidence."""
    if raw.get('engine') != 'Windows.Media.Ocr' or raw.get('execution') != 'ACTUAL_LOCAL_ENGINE' or raw.get('sourceSha256') != source_hash:
        raise ValueError('engine_receipt_mismatch')
    pages = raw['pages']
    if [p['page'] for p in pages] != list(range(start, end + 1)):
        raise ValueError('engine_page_mapping_mismatch')
    blocks, blank = [], []
    for page, image in zip(pages, images, strict=True):
        if page['imageSha256'] != image['imageSha256'] or page['width'] != image['width'] or page['height'] != image['height']:
            raise ValueError('engine_image_binding_mismatch')
        lines = page['lines']
        if not isinstance(lines, list) or len(lines) > 10000:
            raise ValueError('engine_line_limit')
        for index, line in enumerate(lines):
            text = line['text']
            if not isinstance(text, str) or len(text) > 10000:
                raise ValueError('engine_text_limit')
            if text.strip():
                blocks.append({'blockId': f"p{page['page']}-b{index}", 'page': page['page'], 'type': 'text', 'text': text})
        # No recognized words is not evidence that a nonwhite scanned page is blank.
        if not any(line['text'].strip() for line in lines) and image['rasterIsWhite']:
            blank.append(page['page'])
    if len(blocks) > 10000 or sum(len(b['text']) for b in blocks) > 1_000_000:
        raise ValueError('document_limit')
    return {'contractVersion': 1, 'sourceSha256': source_hash, 'resultRevision': 1,
            'parserVersion': 'Windows.Media.Ocr/' + raw['osVersion'], 'adapterVersion': 'windows-local-1',
            'tier': 'basic', 'totalPages': total, 'requestedRange': [start, end],
            'processedPages': list(range(start, end + 1)), 'blankPages': blank, 'failedPages': [],
            'complete': start == 1 and end == total,
            'warnings': ['Actual local Windows OCR; not MinerU. Text/formulas require source-page confirmation; no semantic math or layout certification.'],
            'blocks': blocks}


def run(source, output, language, start, end):
    from pypdf import PdfReader
    import pypdfium2 as pdfium
    if os.name != 'nt':
        raise ValueError('windows_required')
    source = source.resolve()
    if source.stat().st_size > MAX_SOURCE:
        raise ValueError('source_limit')
    source_hash = digest(source)
    reader = PdfReader(source, strict=True)
    if reader.is_encrypted:
        raise ValueError('encrypted_pdf')
    total = len(reader.pages)
    end = min(total, 5) if end is None else end
    if not (1 <= total <= 200 and 1 <= start <= end <= total and end - start < 5):
        raise ValueError('page_range_limit')
    output.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(source, output / 'source.pdf')
    if digest(output / 'source.pdf') != source_hash:
        raise ValueError('source_changed')
    images = []
    with pdfium.PdfDocument(output / 'source.pdf') as pdf:
        if len(pdf) != total:
            raise ValueError('pdf_page_count_mismatch')
        for number in range(start, end + 1):
            page = pdf[number - 1]
            try:
                width, height = page.get_size()
                if min(width, height) <= 0 or max(width, height) > 20000:
                    raise ValueError('page_geometry_limit')
                scale = min(2.0, 2500 / max(width, height))
                bitmap = page.render(scale=scale)
                try:
                    image = bitmap.to_pil().convert('RGB')
                    try:
                        if image.width * image.height > 32_000_000:
                            raise ValueError('render_limit')
                        path = output / f'page-{number:03d}.png'
                        image.save(path, 'PNG')
                        images.append({'page': number, 'image': str(path.resolve()), 'imageSha256': digest(path),
                                       'width': image.width, 'height': image.height,
                                       'rasterIsWhite': image.getextrema() == ((255, 255),) * 3})
                    finally:
                        image.close()
                finally:
                    bitmap.close()
            finally:
                page.close()
    manifest = output / 'engine-input.json'
    write_new(manifest, {'contractVersion': 1, 'sourceSha256': source_hash, 'pages': images})
    script = ROOT / 'scripts/b_windows_ocr.ps1'
    command = ['powershell.exe', '-NoProfile', '-NonInteractive', '-File', str(script),
               '-InputManifest', str(manifest.resolve()), '-OutputFile', str((output / 'raw-ocr.json').resolve()), '-Language', language]
    started = time.time()
    result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=300)
    (output / 'engine.stdout.log').write_bytes(result.stdout)
    (output / 'engine.stderr.log').write_bytes(result.stderr)
    write_new(output / 'ENGINE_RUN.json', {'command': command, 'cwd': str(ROOT), 'exitCode': result.returncode,
              'seconds': round(time.time() - started, 3), 'scriptSha256': digest(script), 'sourceSha256': source_hash,
              'languageRequested': language, 'networkOrModelUsed': False})
    if result.returncode:
        raise ValueError('local_windows_ocr_failed')
    raw_path = output / 'raw-ocr.json'
    if raw_path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('engine_output_limit')
    raw = json.loads(raw_path.read_text(encoding='utf-8-sig'))
    if raw.get('language') != language:
        raise ValueError('engine_language_changed')
    document = normalize(raw, source_hash, total, start, end, images)
    if digest(source) != source_hash or any(digest(p['image']) != p['imageSha256'] for p in images):
        raise ValueError('input_changed_during_recognition')
    write_new(output / 'document.json', document)
    asset = {'assetId': 'document', 'path': 'document.json', 'mediaType': 'application/json',
             'size': (output / 'document.json').stat().st_size, 'sha256': digest(output / 'document.json')}
    write_new(output / 'manifest.json', {**{k: v for k, v in document.items() if k != 'blocks'}, 'assets': [asset]})
    write_new(output / 'RECOGNITION_RECEIPT.json', {'status': 'PASS', 'execution': 'ACTUAL_LOCAL_ENGINE',
              'engine': raw['engine'], 'osVersion': raw['osVersion'], 'language': raw['language'],
              'sourceSha256': source_hash, 'adapterSha256': digest(Path(__file__)),
              'engineScriptSha256': digest(script), 'rawOutputSha256': digest(raw_path),
              'documentSha256': digest(output / 'document.json'), 'manifestSha256': digest(output / 'manifest.json'),
              'processedPages': document['processedPages'], 'confirmedByUser': False,
              'recognitionQuality': 'NOT_SCORED', 'mathematics': 'NOT_EVALUATED', 'layout': 'NOT_EVALUATED'})
    return {'status': 'PASS', 'engine': raw['engine'], 'processedPages': document['processedPages'],
            'documentSha256': digest(output / 'document.json'), 'quality': 'NOT_SCORED'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--language', required=True, help='Installed exact WinRT language tag; never auto-switch')
    parser.add_argument('--start', type=int, default=1)
    parser.add_argument('--end', type=int)
    args = parser.parse_args()
    try:
        print(json.dumps(run(args.source, args.out, args.language, args.start, args.end)))
    except (ValueError, OSError, KeyError, TypeError, subprocess.TimeoutExpired):
        print(json.dumps({'status': 'FAIL', 'errorCode': 'local_ocr_input_or_execution_failed'}), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
