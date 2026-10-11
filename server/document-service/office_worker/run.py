import hashlib
import json
import os
import re
import subprocess
import time
from pathlib import Path
from office_worker.package import inspect_package
from office_worker.check_output import validate_pdf


def version():
    value = subprocess.run(['soffice', '--version'], check=True, capture_output=True, text=True, timeout=20).stdout
    return value.strip().split()[1]


def property_value(name, value):
    from com.sun.star.beans import PropertyValue
    item = PropertyValue(); item.Name = name; item.Value = value
    return item


def export(source, extension, target):
    import uno
    from com.sun.star.document.MacroExecMode import NEVER_EXECUTE
    from com.sun.star.document.UpdateDocMode import NO_UPDATE
    profile = Path('/tmp/office-profile'); profile.mkdir()
    process = subprocess.Popen(['soffice', '--headless', '--nologo', '--nodefault', '--norestore',
                                '-env:UserInstallation=' + profile.as_uri(),
                                '--accept=pipe,name=mathdoc_office;urp;StarOffice.ComponentContext'],
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    document = None; desktop = None
    try:
        local = uno.getComponentContext()
        resolver = local.ServiceManager.createInstanceWithContext('com.sun.star.bridge.UnoUrlResolver', local)
        context = None
        for _ in range(100):
            try:
                context = resolver.resolve('uno:pipe,name=mathdoc_office;urp;StarOffice.ComponentContext'); break
            except Exception:
                if process.poll() is not None:
                    raise ValueError('office_start_failed') from None
                time.sleep(0.1)
        if context is None:
            raise ValueError('office_start_failed')
        desktop = context.ServiceManager.createInstanceWithContext('com.sun.star.frame.Desktop', context)
        document = desktop.loadComponentFromURL(source.as_uri(), '_blank', 0, tuple(property_value(key, value) for key, value in {
            'Hidden': True, 'ReadOnly': True, 'MacroExecutionMode': NEVER_EXECUTE,
            'UpdateDocMode': NO_UPDATE, 'PickListEntry': False,
        }.items()))
        service = {'docx': 'com.sun.star.text.TextDocument', 'xlsx': 'com.sun.star.sheet.SpreadsheetDocument',
                   'pptx': 'com.sun.star.presentation.PresentationDocument'}[extension]
        if document is None or not document.supportsService(service):
            raise ValueError('office_load_failed')
        if extension == 'xlsx':
            document.calculateAll()
            count = 0
            for sheet in document.getSheets():
                ranges = sheet.queryContentCells(16)  # CellFlags.FORMULA
                for address in ranges.getRangeAddresses():
                    count += (address.EndRow-address.StartRow+1)*(address.EndColumn-address.StartColumn+1)
                    if count > 100000:
                        raise ValueError('office_formula_limit')
                    for row in range(address.StartRow, address.EndRow+1):
                        for col in range(address.StartColumn, address.EndColumn+1):
                            if sheet.getCellByPosition(col, row).getError():
                                raise ValueError('formula_recalculation_failed')
        filters = {'docx': 'writer_pdf_Export', 'xlsx': 'calc_pdf_Export', 'pptx': 'impress_pdf_Export'}
        data = tuple(property_value(key, value) for key, value in {
            'UseLosslessCompression': True, 'ReduceImageResolution': False,
            'ExportNotes': False, 'ExportNotesPages': False, 'ExportHiddenSlides': False,
            'ExportFormFields': False, 'ExportBookmarks': True,
        }.items())
        document.storeToURL(target.as_uri(), (property_value('FilterName', filters[extension]),
                                            property_value('FilterData', data), property_value('Overwrite', False)))
    finally:
        if document is not None:
            document.close(True)
        if desktop is not None:
            desktop.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait()


def main():
    output = Path('/output')
    try:
        options = json.loads(Path('/input/request.json').read_text())
        extension = options['extension']; source = Path('/input/source.' + extension)
        fonts = inspect_package(source, extension, options['sourceSha256'])
        actual_version = version()
        if actual_version != options['rendererVersion']:
            raise ValueError('renderer_version_changed')
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
