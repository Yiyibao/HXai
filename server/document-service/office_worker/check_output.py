"""Independent PDF validation, run in a second offline container without the source."""
import hashlib
import json
from pathlib import Path
from pypdf import PdfReader


def validate_pdf(path):
    if path.is_symlink() or not path.is_file() or not 1 <= path.stat().st_size <= 30 * 1024 * 1024:
        raise ValueError('invalid_conversion_pdf')
    with path.open('rb') as stream:
        if not stream.read(5) == b'%PDF-':
            raise ValueError('invalid_conversion_pdf')
    reader = PdfReader(path, strict=True)
    if reader.is_encrypted or not 1 <= len(reader.pages) <= 200:
        raise ValueError('conversion_page_limit')
    catalog = reader.trailer['/Root']
    if '/AA' in catalog or '/AcroForm' in catalog:
        raise ValueError('active_conversion_pdf')
    opening = catalog.get('/OpenAction')
    if opening is not None:
        opening = opening.get_object()
        # LibreOffice writes a passive destination to the first page, not executable code.
        if not isinstance(opening, list) or not 2 <= len(opening) <= 6 or opening[1] not in {'/XYZ', '/Fit', '/FitH', '/FitV', '/FitR', '/FitB', '/FitBH', '/FitBV'}:
            raise ValueError('active_conversion_pdf')
    names = catalog.get('/Names', {})
    if hasattr(names, 'get_object'):
        names = names.get_object()
    if '/JavaScript' in names or '/EmbeddedFiles' in names:
        raise ValueError('active_conversion_pdf')
    for page in reader.pages:
        if '/AA' in page:
            raise ValueError('active_conversion_pdf')
        for reference in page.get('/Annots', []):
            annotation = reference.get_object()
            if '/AA' in annotation or annotation.get('/Subtype') in {'/FileAttachment', '/Widget'}:
                raise ValueError('active_conversion_pdf')
            action = annotation.get('/A')
            if action:
                action = action.get_object()
                if action.get('/S') not in {'/URI', '/GoTo'} or '/Next' in action:
                    raise ValueError('active_conversion_pdf')
                if action.get('/S') == '/URI' and not str(action.get('/URI', '')).lower().startswith(('http://', 'https://', 'mailto:')):
                    raise ValueError('active_conversion_pdf')
    return len(reader.pages)


def check_output(directory):
    manifest = json.loads((directory / 'manifest.json').read_text())
    path = directory / 'output.pdf'
    if manifest['pageCount'] != validate_pdf(path):
        raise ValueError('conversion_page_mismatch')
    if manifest['assets'] != [{'assetId': 'pdf', 'path': 'output.pdf', 'mediaType': 'application/pdf',
                              'size': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}]:
        raise ValueError('conversion_asset_mismatch')


if __name__ == '__main__':
    check_output(Path('/output'))
