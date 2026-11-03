import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path
from office_worker.package import inspect_package
from office_worker.check_output import validate_pdf
from office_worker.engine import SOFFICE, office_document, property_value, save_calculated


def version():
    value = subprocess.run([SOFFICE, '--version'], check=True, capture_output=True, text=True, timeout=20).stdout
    return value.strip().split()[1]




def export(source, extension, target):
    with office_document(source, extension) as document:
        if extension == 'xlsx':
            # A calculateAll return alone does not verify saved formula caches.
            with tempfile.TemporaryDirectory(prefix='mathdoc-pdf-recalc-') as staging:
                save_calculated(document, Path(staging) / 'recalculated.xlsx')
        filters = {'docx': 'writer_pdf_Export', 'xlsx': 'calc_pdf_Export', 'pptx': 'impress_pdf_Export'}
        data = tuple(property_value(key, value) for key, value in {
            'UseLosslessCompression': True, 'ReduceImageResolution': False,
            'ExportNotes': False, 'ExportNotesPages': False, 'ExportHiddenSlides': False,
            'ExportFormFields': False, 'ExportBookmarks': True,
        }.items())
        document.storeToURL(target.resolve().as_uri(), (property_value('FilterName', filters[extension]),
                                                       property_value('FilterData', data), property_value('Overwrite', False)))


def main(input_directory=Path('/input'), output=Path('/output')):
    try:
        options = json.loads((input_directory / 'request.json').read_text())
        extension = options['extension']; source = input_directory / ('source.' + extension)
        fonts = inspect_package(source, extension, options['sourceSha256'])
        actual_version = version()
        if actual_version != options['rendererVersion']:
            raise ValueError('renderer_version_changed')
        if options.get('operation') == 'xlsx_recalculate':
            from office_worker.recalculation import produce
            produce(source, output, options, actual_version)
            return
        installed = subprocess.run(['fc-list', '--format', '%{family}\n'], capture_output=True, text=True, timeout=10, check=True).stdout
        families = {name.strip().casefold() for line in installed.splitlines() for name in line.split(',')}
        missing = [font for font in fonts if font.casefold() not in families]
        target = output / 'output.pdf'
        export(source, extension, target)
        pages = validate_pdf(target)
        manifest = {'contractVersion': 1, 'operation': 'office_pdf', 'sourceSha256': options['sourceSha256'],
                    'sourceExtension': extension, 'renderer': 'LibreOffice', 'rendererVersion': actual_version,
                    'renderOptions': options['renderOptions'], 'pageCount': pages,
                    'warnings': ['LibreOffice compatibility rendering; verify complex Office layout.'] +
                                (['Font substitution: ' + ', '.join(missing)] if missing else []),
                    'assets': [{'assetId': 'pdf', 'path': 'output.pdf', 'mediaType': 'application/pdf',
                                'size': target.stat().st_size, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}]}
        (output / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
    except Exception as error:
        code = str(error) if isinstance(error, ValueError) and re.fullmatch(r'[a-z_]{1,64}', str(error)) else 'office_conversion_failed'
        (output / 'error.json').write_text(json.dumps({'errorCode': code}))
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
