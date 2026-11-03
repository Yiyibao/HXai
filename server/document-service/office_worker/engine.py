"""Host-only Office adapter. Callers retain authorization and sandbox responsibilities."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

from office_worker.calculation import calculate, verify_saved
from office_worker.package import inspect_package
from office_worker.contracts import WITNESS_LIMIT


SOFFICE = 'soffice.com' if os.name == 'nt' else 'soffice'


def property_value(name, value):
    from com.sun.star.beans import PropertyValue
    item = PropertyValue()
    item.Name = name
    item.Value = value
    return item


@contextmanager
def office_document(source, extension):
    import uno
    from com.sun.star.document.MacroExecMode import NEVER_EXECUTE
    from com.sun.star.document.UpdateDocMode import NO_UPDATE
    with tempfile.TemporaryDirectory(prefix='mathdoc-office-') as profile:
        pipe = 'mathdoc_' + uuid.uuid4().hex
        process = subprocess.Popen([SOFFICE, '--headless', '--nologo', '--nodefault', '--norestore',
                                    '-env:UserInstallation=' + Path(profile).as_uri(),
                                    '--accept=pipe,name=' + pipe + ';urp;StarOffice.ComponentContext'],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        document = None
        desktop = None
        try:
            local = uno.getComponentContext()
            resolver = local.ServiceManager.createInstanceWithContext('com.sun.star.bridge.UnoUrlResolver', local)
            context = None
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                try:
                    context = resolver.resolve('uno:pipe,name=' + pipe + ';urp;StarOffice.ComponentContext')
                    break
                except Exception:
                    if process.poll() is not None:
                        raise ValueError('office_start_failed') from None
                    time.sleep(0.1)
            if context is None:
                raise ValueError('office_start_failed')
            desktop = context.ServiceManager.createInstanceWithContext('com.sun.star.frame.Desktop', context)
            document = desktop.loadComponentFromURL(source.resolve().as_uri(), '_blank', 0, tuple(property_value(key, value) for key, value in {
                'Hidden': True, 'ReadOnly': True, 'MacroExecutionMode': NEVER_EXECUTE,
                'UpdateDocMode': NO_UPDATE, 'PickListEntry': False,
            }.items()))
            service = {'docx': 'com.sun.star.text.TextDocument', 'xlsx': 'com.sun.star.sheet.SpreadsheetDocument',
                       'pptx': 'com.sun.star.presentation.PresentationDocument'}[extension]
            if document is None or not document.supportsService(service):
                raise ValueError('office_load_failed')
            yield document
        finally:
            # Nested cleanup ensures a close error cannot leave our engine running.
            try:
                if document is not None:
                    document.close(True)
            finally:
                try:
                    if desktop is not None:
                        desktop.terminate()
                finally:
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        if os.name == 'nt':
                            # soffice.com is a launcher; stop only its owned process tree.
                            subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                                           check=True, capture_output=True, timeout=10)
                        else:
                            process.kill()
                        process.wait(timeout=10)


def save_calculated(document, target, witness=None, source_sha256=None):
    expected = calculate(document)
    document.storeToURL(target.resolve().as_uri(), (property_value('FilterName', 'Calc MS Excel 2007 XML'),
                                                 property_value('Overwrite', False)))
    evidence = verify_saved(target, expected)
    if witness is not None:
        def address(column, row):
            letters = ''
            column += 1
            while column:
                column, digit = divmod(column - 1, 26)
                letters = chr(65 + digit) + letters
            return letters + str(row + 1)
        value = {'contractVersion': 1, 'sourceSha256': source_sha256, 'outputSha256': evidence['sha256'],
                 'sheetNames': list(document.getSheets().getElementNames()),
                 'cells': [{'sheet': sheet, 'cell': address(column, row), 'number': number, 'text': text}
                           for (sheet, column, row), (number, text) in expected.items()]}
        encoded = json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')
        if len(encoded) > WITNESS_LIMIT:
            raise ValueError('calculation_evidence_limit')
        with witness.open('xb') as output:
            output.write(encoded)
    return evidence


def recalculate_xlsx(source, output, expected_sha256, witness=None):
    """Save a new immutable XLSX only after engine calculation and independent cache readback."""
    source, output = Path(source), Path(output)
    if source.is_symlink() or not source.is_file():
        raise ValueError('invalid_office_input')
    if source.resolve() == output.resolve() or output.exists() or output.is_symlink():
        raise ValueError('office_output_must_be_new')
    if witness is not None and (witness.exists() or witness.is_symlink() or witness.resolve() in (source.resolve(), output.resolve())):
        raise ValueError('office_output_must_be_new')
    with tempfile.TemporaryDirectory(prefix='mathdoc-recalc-', dir=output.parent) as staging:
        frozen = Path(staging) / 'source.xlsx'
        # The engine must read the bytes whose digest was checked, not a live path
        # that can be temporarily replaced and restored before the final hash check.
        with source.open('rb') as input, frozen.open('xb') as target:
            total = 0
            while chunk := input.read(65536):
                total += len(chunk)
                if total > 30 * 1024 * 1024:
                    raise ValueError('invalid_office_input')
                target.write(chunk)
        inspect_package(frozen, 'xlsx', expected_sha256)
        saved = Path(staging) / 'recalculated.xlsx'
        with office_document(frozen, 'xlsx') as document:
            evidence = save_calculated(document, saved, witness, expected_sha256)
        if hashlib.sha256(source.read_bytes()).hexdigest() != expected_sha256:
            raise ValueError('source_digest_mismatch')
        # Same-filesystem exclusive atomic publication: never overwrite a raced output.
        try:
            os.link(saved, output)
        except FileExistsError:
            raise ValueError('office_output_must_be_new') from None
    return {**evidence, 'sourceSha256': expected_sha256, 'renderer': 'LibreOffice',
            'recalculation': 'VERIFIED', 'layout': 'NOT_VERIFIED'}
