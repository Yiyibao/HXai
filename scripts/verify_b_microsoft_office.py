"""Qualify frozen synthetic files in installed Microsoft Office, without touching WPS.
No installs, account access, model requests or user documents. Output directory must be new.
Office native save/reopen/calculation and independent ZIP/PDF/pixel readbacks are separate.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from verify_b_windows_ocr import digest, write_new
from verify_b_xlsx_readback import make_source, parts, package, X

ROOT = Path(__file__).resolve().parents[1]


def wait_owned_exit(pid):
    """Wait after the COM host exited; never terminate any Office process."""
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    kernel.WaitForSingleObject.restype = wintypes.DWORD
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.OpenProcess(0x00100000, False, pid)
    if not handle:
        if ctypes.get_last_error() == 87:
            return True
        raise OSError('owned_office_exit_unreadable')
    try:
        return kernel.WaitForSingleObject(handle, 20000) == 0
    finally:
        kernel.CloseHandle(handle)


def check_outputs(folder, jobs, date_serial):
    from pypdf import PdfReader
    import pypdfium2 as pdfium
    from PIL import ImageChops
    office = folder / 'office'
    record = json.loads((office / 'OFFICE_RUN.json').read_text(encoding='utf-8-sig'))
    if record['status'] != 'PASS' or len(record['jobs']) != len(jobs):
        raise ValueError('office_job_coverage')
    readbacks = []
    for job, row in zip(jobs, record['jobs'], strict=True):
        source = Path(job['source']); saved = office / (job['label'] + '.' + job['kind'])
        pdf_path = office / (job['label'] + '.pdf')
        if (row['label'] != job['label'] or digest(source) != job['sourceSha256'] or
                digest(saved) != row['outputSha256'] or digest(pdf_path) != row['pdfSha256']):
            raise ValueError('artifact_digest_mismatch')
        pdf = PdfReader(pdf_path, strict=True)
        texts = [p.extract_text() for p in pdf.pages]
        check = {'label': job['label'], 'kind': job['kind'], 'sourceUnchanged': True,
                 'sourceSha256': job['sourceSha256'], 'savedSha256': digest(saved),
                 'pdfSha256': digest(pdf_path), 'pdfPhysicalPages': len(pdf.pages)}
        if job['kind'] == 'docx':
            w = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
            m = '{http://schemas.openxmlformats.org/officeDocument/2006/math}'
            with ZipFile(source) as z:
                before = ET.fromstring(z.read('word/document.xml'))
            with ZipFile(saved) as z:
                after = ET.fromstring(z.read('word/document.xml'))
            before_math = list(before.iter(m + 'oMath')); after_math = list(after.iter(m + 'oMath'))
            if len(before_math) != len(after_math) or not before_math:
                raise ValueError('editable_omml_lost')
            if [''.join(n.itertext()) for n in before_math] != [''.join(n.itertext()) for n in after_math]:
                raise ValueError('omml_content_changed')
            expected = 'TARGET new precise phrase END' if job['label'] == 'word-edited' else 'TARGET old phrase END'
            if job['label'] != 'word-notes':
                if expected not in '\n'.join(texts) or len(pdf.pages) < 3:
                    raise ValueError('word_actual_render_missing_content')
            if len(pdf.pages) != row['metrics']['pages']:
                raise ValueError('word_physical_page_count_mismatch')
            check['editableOmmlCount'] = len(after_math)
        elif job['kind'] == 'xlsx':
            saved_parts = parts(saved)
            workbook = ET.fromstring(saved_parts['xl/workbook.xml'])
            relationships = ET.fromstring(saved_parts['xl/_rels/workbook.xml.rels'])
            paths = {r.attrib['Id']: 'xl/' + r.attrib['Target'].removeprefix('/xl/') for r in relationships}
            values = {}
            for sheet in workbook.find(f'{{{X}}}sheets'):
                rel = sheet.attrib['{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id']
                path = paths[rel]
                if path.startswith('xl/xl/'):
                    path = path[3:]
                xml = ET.fromstring(saved_parts[path])
                values[sheet.attrib['name']] = {c.attrib['r']: {'type': c.attrib.get('t', 'n'),
                    'formula': c.findtext(f'{{{X}}}f'), 'value': c.findtext(f'{{{X}}}v')} for c in xml.iter(f'{{{X}}}c')}
            data, summary = values['Data'], values['Summary']
            if data['B2']['value'] != '21' or data['E2']['value'] != '22' or summary['A1']['value'] != '22':
                raise ValueError('saved_recalculation_cache_mismatch')
            if summary['B1']['value'] != 'sum=22' or summary['C1'] != {'type': 'b', 'formula': 'Data!A2>0', 'value': '1'}:
                raise ValueError('saved_formula_result_type_mismatch')
            if float(data['D2']['value']) != date_serial or row['metrics']['dataD2Text'] == str(date_serial):
                raise ValueError('date_value_or_format_lost')
            if not any(k.startswith('xl/charts/') and k.endswith('.xml') for k in saved_parts):
                raise ValueError('chart_lost')
            check.update(actualFullRebuildAndReopen=True, crossSheetValuesReadBack=True,
                         dateSerial=date_serial, typedBooleanReadBack=True, chartRetained=True)
        else:
            fixture = json.loads((ROOT / 'HXai_CHANGE_REVIEW/B03-PRESENTATION-PDF-20261019/fixtures/FIXTURE.json').read_text(encoding='utf-8'))
            slides = row['metrics']['allLogicalSlides']
            if [s['hidden'] for s in slides] != fixture['hidden'] or [s['logicalPage'] for s in slides] != list(range(1, 6)):
                raise ValueError('logical_hidden_coverage_mismatch')
            pdf_logical = list(range(1, 6))
            if len(pdf.pages) != len(pdf_logical):
                raise ValueError('missing_logical_slides_in_pdf')
            if row['metrics'].get('pdfRenderView') != 'ALL_LOGICAL_SLIDES_UNHIDDEN_IN_MEMORY_ONLY' or not row['metrics'].get('savedPresentationUnchangedByRender'):
                raise ValueError('all_logical_render_view_not_attested')
            for number, title in enumerate(fixture['titles'], 1):
                if title not in slides[number - 1]['text']:
                    raise ValueError('logical_slide_text_mapping_lost')
                from PIL import Image
                with Image.open(office / f"{job['label']}-before-{number}.png") as before, Image.open(office / f"{job['label']}-after-{number}.png") as after:
                    if before.size != after.size or ImageChops.difference(before.convert('RGB'), after.convert('RGB')).getbbox() is not None:
                        raise ValueError('powerpoint_saved_visual_changed')
            if any(fixture['titles'][logical - 1] not in texts[index] for index, logical in enumerate(pdf_logical)):
                raise ValueError('pdf_physical_to_logical_mapping_lost')
            check.update(allLogicalSlidesRenderedToPng=True, hiddenSlidesPreserved=True,
                         allSlideRoundtripPixelsEqual=True, pdfPageToLogicalPage=pdf_logical,
                         pdfHiddenSlidesIncluded=True,
                         pdfRenderView='ALL_LOGICAL_SLIDES_UNHIDDEN_IN_MEMORY_ONLY')
        rendered = []
        with pdfium.PdfDocument(pdf_path) as native:
            for index in range(len(native)):
                page = native[index]
                try:
                    bitmap = page.render(scale=min(1.5, 1000 / max(page.get_size())))
                    try:
                        image = bitmap.to_pil()
                        try:
                            target = office / f"{job['label']}-pdf-{index + 1}.png"
                            image.save(target, 'PNG'); rendered.append({'page': index + 1, 'sha256': digest(target)})
                        finally:
                            image.close()
                    finally:
                        bitmap.close()
                finally:
                    page.close()
        check['independentPdfRenders'] = rendered
        readbacks.append(check)
    write_new(folder / 'INDEPENDENT_READBACK.json', {'status': 'PASS', 'engine': 'Microsoft Office, not LibreOffice/WPS', 'jobs': readbacks})
    return readbacks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    if os.name != 'nt':
        parser.error('Windows and already-installed Microsoft Office required')
    folder = args.run_dir.resolve(); folder.mkdir(parents=True, exist_ok=False)
    inputs = folder / 'inputs'; inputs.mkdir()
    jobs = []
    for label, relative in [('word-source', 'native/source-A.docx'), ('word-edited', 'native/edited-A.docx'), ('word-notes', 'native/created-notes.docx')]:
        target = inputs / (label + '.docx')
        shutil.copyfile(ROOT / 'HXai_CHANGE_REVIEW/B02-DOCX-PRECISION-20261019' / relative, target)
        jobs.append({'label': label, 'kind': 'docx', 'source': str(target), 'sourceSha256': digest(target)})
    base = inputs / 'workbook-original.xlsx'; date_serial = make_source(base)
    content = parts(base); xml = ET.fromstring(content['xl/worksheets/sheet1.xml'])
    xml.find(f'.//{{{X}}}c[@r="A2"]/{{{X}}}v').text = '7'
    # A new explicit layout fixture, not a silent repair of the preserved narrow-date counterexample.
    columns = ET.Element(f'{{{X}}}cols')
    ET.SubElement(columns, f'{{{X}}}col', {'min': '4', 'max': '4', 'width': '14', 'customWidth': '1'})
    xml.insert(list(xml).index(xml.find(f'{{{X}}}sheetData')), columns)
    content['xl/worksheets/sheet1.xml'] = ET.tostring(xml)
    target = inputs / 'workbook-edited.xlsx'; package(target, content)
    jobs.append({'label': 'excel-rich', 'kind': 'xlsx', 'source': str(target), 'sourceSha256': digest(target)})
    target = inputs / 'presentation.pptx'
    shutil.copyfile(ROOT / 'HXai_CHANGE_REVIEW/B03-PRESENTATION-PDF-20261019/fixtures/source.pptx', target)
    jobs.append({'label': 'powerpoint-logical', 'kind': 'pptx', 'source': str(target), 'sourceSha256': digest(target)})
    manifest = folder / 'INPUTS.json'; write_new(manifest, {'contractVersion': 1, 'fixtureRevision': 2, 'layout': 'Explicit date column D width 14; original narrow-column FAIL retained separately.', 'provenance': 'PUBLIC_SYNTHETIC_NATIVE_FIXTURES', 'jobs': jobs, 'dateSerial': date_serial})
    script = ROOT / 'scripts/verify_b_microsoft_office.ps1'
    office = folder / 'office'; office.mkdir()
    commands, actual, failures = [], [], []
    failed_kinds = set()
    for job in jobs:
        if job['kind'] in failed_kinds:
            failures.append({'label': job['label'], 'status': 'BLOCKED', 'reason': 'Earlier operation of this Office kind did not stop safely.'})
            continue
        job_manifest = folder / (job['label'] + '-input.json')
        write_new(job_manifest, {'contractVersion': 1, 'jobs': [job]})
        job_output = folder / (job['label'] + '-com')
        command = ['powershell.exe', '-NoProfile', '-NonInteractive', '-File', str(script), '-InputManifest', str(job_manifest), '-OutputDirectory', str(job_output)]
        result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=240)
        (folder / (job['label'] + '.stdout.log')).write_bytes(result.stdout)
        (folder / (job['label'] + '.stderr.log')).write_bytes(result.stderr)
        commands.append({'command': command, 'cwd': str(ROOT), 'exitCode': result.returncode, 'scriptSha256': digest(script), 'inputSha256': digest(job_manifest)})
        if result.returncode:
            failures.append({'label': job['label'], 'status': 'FAIL', 'reason': 'actual_office_operation_failed'})
            failed_kinds.add(job['kind'])
            continue
        row = json.loads((job_output / 'OFFICE_RUN.json').read_text(encoding='utf-8-sig'))['jobs'][0]
        if not wait_owned_exit(row['ownedProcessId']):
            failures.append({'label': job['label'], 'status': 'FAIL', 'reason': 'owned_process_still_running_after_com_host_exit'})
            failed_kinds.add(job['kind'])
            continue
        row['ownedProcessExitConfirmedAfterHostExit'] = True
        actual.append(row)
        for path in job_output.iterdir():
            if path.is_file() and path.name != 'OFFICE_RUN.json':
                shutil.copyfile(path, office / path.name)
    write_new(folder / 'COMMAND.json', {'commands': commands, 'sourceManifestSha256': digest(manifest)})
    if failures:
        write_new(folder / 'OFFICE_FAILURE.json', {'status': 'FAIL', 'failures': failures, 'completed': actual})
        print(json.dumps({'status': 'FAIL', 'stage': 'actual_microsoft_office'})); return 1
    write_new(office / 'OFFICE_RUN.json', {'status': 'PASS', 'engine': 'Actual Microsoft Office COM', 'jobs': actual, 'ownedProcessesStopped': True, 'wpsAccessed': False})
    try:
        readbacks = check_outputs(folder, jobs, date_serial)
    except (ValueError, KeyError, OSError, AssertionError) as error:
        write_new(folder / 'READBACK_FAILURE.json', {'status': 'FAIL', 'stage': 'independent_readback', 'errorCode': str(error)})
        print(json.dumps({'status': 'FAIL', 'stage': 'independent_readback'})); return 1
    print(json.dumps({'status': 'PASS', 'actualOfficeJobs': len(readbacks), 'wps': 'NOT_RUN; user process not touched'}))
    return 0


if __name__ == '__main__':
    sys.exit(main())
