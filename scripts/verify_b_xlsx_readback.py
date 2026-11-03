"""B1a offline smoke: real MaterialEditor -> real UNO -> saved XLSX/PDF readback.

Uses installed Kotlin/MuPDF jars, LibreOffice's UNO Python and system pypdf.
No installation, network, paid endpoint, production, Gradle or emulator changes.
Run into a NEW --run-dir; never overwrite historical evidence.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / 'server/document-service'
X = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
P = 'http://schemas.openxmlformats.org/package/2006/relationships'
C = 'http://schemas.openxmlformats.org/drawingml/2006/chart'
D = 'http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing'
A = 'http://schemas.openxmlformats.org/drawingml/2006/main'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parts(path):
    with ZipFile(path) as archive:
        return {entry.filename: archive.read(entry) for entry in archive.infolist()}


def package(path, contents):
    with ZipFile(path, 'x', ZIP_DEFLATED) as archive:
        for name, data in contents.items():
            archive.writestr(name, data)


def make_source(path):
    contents = parts(ROOT / 'qa/p0-completion-20261009/samples/rich-source.xlsx')
    workbook = ET.fromstring(contents['xl/workbook.xml'])
    ET.SubElement(workbook.find(f'{{{X}}}sheets'), f'{{{X}}}sheet', {'name': 'Summary', 'sheetId': '2', 'state': 'hidden', f'{{{R}}}id': 'summary'})
    # Exercise the server's legitimate UTF-16 input, not a byte-NUL rejection.
    contents['xl/workbook.xml'] = ET.tostring(workbook, encoding='utf-16', xml_declaration=True)
    relations = ET.fromstring(contents['xl/_rels/workbook.xml.rels'])
    ET.SubElement(relations, f'{{{P}}}Relationship', {'Id': 'summary', 'Type': R + '/worksheet', 'Target': 'worksheets/summary9.xml'})
    contents['xl/_rels/workbook.xml.rels'] = ET.tostring(relations)
    styles = ET.fromstring(contents['xl/styles.xml'])
    xfs = styles.find(f'{{{X}}}cellXfs')
    date_style = len(xfs)
    ET.SubElement(xfs, f'{{{X}}}xf', {'numFmtId': '14', 'fontId': '0', 'fillId': '0', 'borderId': '0', 'xfId': '0'})
    xfs.set('count', str(len(xfs)))
    contents['xl/styles.xml'] = ET.tostring(styles)
    worksheet = ET.fromstring(contents['xl/worksheets/sheet1.xml'])
    rows = worksheet.find(f'{{{X}}}sheetData')
    def cell(row, address, body, attrs=None):
        node = ET.SubElement(row, f'{{{X}}}c', {'r': address, **(attrs or {})})
        for kind, value in body:
            ET.SubElement(node, f'{{{X}}}{kind}').text = value
    cell(rows[0], 'D1', [('v', 'Date')], {'t': 'str'})
    cell(rows[0], 'E1', [('v', 'Cross sheet')], {'t': 'str'})
    serial = (datetime.date(2026, 10, 12) - datetime.date(1899, 12, 30)).days
    cell(rows[1], 'D2', [('v', str(serial))], {'s': str(date_style)})
    cell(rows[1], 'E2', [('f', 'Summary!A1'), ('v', '7')])
    contents['xl/worksheets/sheet1.xml'] = ET.tostring(worksheet)
    contents['xl/worksheets/summary9.xml'] = (f'<worksheet xmlns="{X}"><sheetData><row r="1">'
        '<c r="A1"><f>Data!B2+1</f><v>7</v></c>'
        '<c r="B1" t="str"><f>"sum="&amp;A1</f><v>sum=7</v></c>'
        '<c r="C1" t="b"><f>Data!A2&gt;0</f><v>1</v></c>'
        '<c r="D1" t="str"><f>""</f><v></v></c>'
        '<c r="E1"><f>0.1+0.2</f><v>0.3</v></c>'
        '</row></sheetData></worksheet>').encode()
    types = ET.fromstring(contents['[Content_Types].xml'])
    namespace = types.tag.split('}')[0] + '}'
    for name, media in [('xl/worksheets/summary9.xml', 'application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml'),
                        ('xl/charts/chart1.xml', 'application/vnd.openxmlformats-officedocument.drawingml.chart+xml')]:
        ET.SubElement(types, namespace + 'Override', {'PartName': '/' + name, 'ContentType': media})
    contents['[Content_Types].xml'] = ET.tostring(types)
    drawing = ET.fromstring(contents['xl/drawings/drawing1.xml'])
    anchor = ET.fromstring(f'<xdr:twoCellAnchor xmlns:xdr="{D}" xmlns:a="{A}" xmlns:c="{C}" xmlns:r="{R}">'
        '<xdr:from><xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>4</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>'
        '<xdr:to><xdr:col>7</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>18</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>'
        '<xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="100" name="Count chart"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>'
        '<xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart">'
        '<c:chart r:id="chart"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>')
    drawing.append(anchor)
    contents['xl/drawings/drawing1.xml'] = ET.tostring(drawing)
    drawing_rels = ET.fromstring(contents['xl/drawings/_rels/drawing1.xml.rels'])
    ET.SubElement(drawing_rels, f'{{{P}}}Relationship', {'Id': 'chart', 'Type': R + '/chart', 'Target': '/xl/charts/chart1.xml'})
    contents['xl/drawings/_rels/drawing1.xml.rels'] = ET.tostring(drawing_rels)
    contents['xl/charts/chart1.xml'] = (f'<c:chartSpace xmlns:c="{C}" xmlns:a="{A}"><c:chart><c:plotArea><c:layout/><c:barChart>'
        '<c:barDir val="col"/><c:grouping val="clustered"/><c:ser><c:idx val="0"/><c:order val="0"/><c:tx><c:v>Count</c:v></c:tx>'
        '<c:cat><c:strRef><c:f>Data!$A$1</c:f><c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>Count</c:v></c:pt></c:strCache></c:strRef></c:cat>'
        '<c:val><c:numRef><c:f>Data!$A$2</c:f><c:numCache><c:formatCode>General</c:formatCode><c:ptCount val="1"/><c:pt idx="0"><c:v>2</c:v></c:pt></c:numCache></c:numRef></c:val>'
        '</c:ser><c:axId val="1"/><c:axId val="2"/></c:barChart><c:catAx><c:axId val="1"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:axPos val="b"/>'
        '<c:crossAx val="2"/><c:crosses val="autoZero"/></c:catAx><c:valAx><c:axId val="2"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:axPos val="l"/>'
        '<c:crossAx val="1"/><c:crosses val="autoZero"/><c:crossBetween val="between"/></c:valAx></c:plotArea><c:plotVisOnly val="1"/></c:chart></c:chartSpace>').encode()
    package(path, contents)
    return serial


def command(log, args, env=None):
    result = subprocess.run([str(x) for x in args], cwd=ROOT, env=env, capture_output=True, timeout=180)
    log.write_bytes(('$ ' + subprocess.list2cmdline([str(x) for x in args]) + '\nCWD: ' + str(ROOT) + '\n').encode() + result.stdout + result.stderr + f'\nexit_code={result.returncode}\n'.encode())
    if result.returncode:
        raise RuntimeError('Command failed: ' + str(log))


def engine_child(folder):
    from office_worker.engine import recalculate_xlsx, office_document
    from office_worker.run import export, version
    from office_worker.package import inspect_package
    from office_worker.calculation import verify_saved
    from office_worker.check_output import validate_pdf
    from pypdf import PdfReader
    source, edited = folder / 'source.xlsx', folder / 'edited.xlsx'
    original_sha, edited_sha = digest(source), digest(edited)
    output = folder / 'recalculated.xlsx'
    evidence = recalculate_xlsx(edited, output, edited_sha)
    assert evidence['formulaCount'] == 7 and evidence['sheetCount'] == 2
    contents = parts(output)
    # Real UNO reopening checks native values, date display, hidden sheet and chart objects.
    with office_document(output, 'xlsx') as doc:
        data, summary = doc.getSheets().getByName('Data'), doc.getSheets().getByName('Summary')
        assert data.getCellRangeByName('A2').getValue() == 7
        assert data.getCellRangeByName('B2').getValue() == 21
        assert data.getCellRangeByName('C2').getString() == '001'
        assert data.getCellRangeByName('E2').getValue() == 22
        assert summary.getCellRangeByName('A1').getValue() == 22
        assert summary.getCellRangeByName('B1').getString() == 'sum=22'
        assert summary.getCellRangeByName('C1').getValue() == 1
        assert summary.getCellRangeByName('D1').getString() == ''
        assert summary.IsVisible is False
        assert data.getCharts().getCount() == 1
        date_cell = data.getCellRangeByName('D2')
        assert date_cell.getValue() == json.loads((folder / 'fixture.json').read_text())['dateSerial']
        date_display = date_cell.getString()
        assert date_display and date_display != str(int(date_cell.getValue()))
    chart_parts = [key for key in contents if key.startswith('xl/charts/chart') and key.endswith('.xml')]
    assert len(chart_parts) == 1
    chart = ET.fromstring(contents[chart_parts[0]])
    cached = [n.text for n in chart.findall(f'.//{{{C}}}numCache/{{{C}}}pt/{{{C}}}v')]
    assert '7' in cached, cached
    pdf = folder / 'output.pdf'
    inspect_package(edited, 'xlsx', edited_sha)
    export(edited, 'xlsx', pdf)
    pages = validate_pdf(pdf)
    text = '\n'.join(page.extract_text() for page in PdfReader(pdf).pages)
    assert '21' in text and '22' in text and 'Count' in text
    # Actual engine formula-error rejection: no output may be published.
    bad_parts = parts(edited)
    sheet = ET.fromstring(bad_parts['xl/worksheets/sheet1.xml'])
    sheet.find(f'.//{{{X}}}c[@r="B2"]/{{{X}}}f').text = 'A2/0'
    bad_parts['xl/worksheets/sheet1.xml'] = ET.tostring(sheet)
    bad = folder / 'formula-error.xlsx'
    package(bad, bad_parts)
    failed_output = folder / 'must-not-exist.xlsx'
    try:
        recalculate_xlsx(bad, failed_output, digest(bad))
    except ValueError as error:
        assert str(error) == 'formula_recalculation_failed', str(error)
    else:
        raise AssertionError('Formula error was reported as success')
    assert not failed_output.exists()
    # Independent saved-file rejection, using real engine output with a corrupted cache.
    corrupt_parts = parts(output)
    worksheet_path = next(key for key in corrupt_parts if key.startswith('xl/worksheets/') and b'A2*3' in corrupt_parts[key])
    root = ET.fromstring(corrupt_parts[worksheet_path])
    root.find(f'.//{{{X}}}c[@r="B2"]/{{{X}}}v').text = '6'
    corrupt_parts[worksheet_path] = ET.tostring(root)
    corrupt = folder / 'wrong-cache.xlsx'
    package(corrupt, corrupt_parts)
    from office_worker.calculation import calculate
    with office_document(output, 'xlsx') as doc:
        expected = calculate(doc)
    try:
        verify_saved(corrupt, expected)
    except ValueError as error:
        assert str(error) == 'formula_readback_mismatch'
    else:
        raise AssertionError('Stale cache was reported as verified')
    # Inject races after a REAL engine session, before the adapter's publication fence.
    from contextlib import contextmanager
    import office_worker.engine as engine
    original_session = engine.office_document
    faults = []
    for fault in ('source_changed', 'output_created', 'transient_source'):
        race_source = folder / (fault + '-source.xlsx')
        package(race_source, parts(edited))
        race_output = folder / (fault + '-output.xlsx')
        race_hash = digest(race_source)
        race_original = race_source.read_bytes()
        @contextmanager
        def raced_session(file, extension):
            if fault == 'transient_source':
                changed = parts(race_source)
                xml = ET.fromstring(changed['xl/worksheets/sheet1.xml'])
                xml.find(f'.//{{{X}}}c[@r="A2"]/{{{X}}}v').text = '8'
                changed['xl/worksheets/sheet1.xml'] = ET.tostring(xml)
                replacement = folder / 'transient-replacement.xlsx'
                package(replacement, changed)
                os.replace(replacement, race_source)
            with original_session(file, extension) as document:
                yield document
            if fault == 'source_changed':
                changed = parts(race_source)
                xml = ET.fromstring(changed['xl/worksheets/sheet1.xml'])
                xml.find(f'.//{{{X}}}c[@r="A2"]/{{{X}}}v').text = '8'
                changed['xl/worksheets/sheet1.xml'] = ET.tostring(xml)
                replacement = folder / 'race-replacement.xlsx'
                package(replacement, changed)
                os.replace(replacement, race_source)
            elif fault == 'output_created':
                race_output.write_bytes(b'concurrently-published-version')
            else:
                race_source.write_bytes(race_original)
        engine.office_document = raced_session
        try:
            if fault == 'transient_source':
                recalculate_xlsx(race_source, race_output, race_hash)
                assert digest(race_source) == race_hash
                with original_session(race_output, 'xlsx') as document:
                    actual = document.getSheets().getByName('Data').getCellRangeByName('B2').getValue()
                    print('Transient source restored; expected B2=21, actual B2=' + str(actual), flush=True)
                    assert actual == 21
                faults.append(fault)
                continue
            try:
                recalculate_xlsx(race_source, race_output, race_hash)
            except ValueError as error:
                expected_code = 'source_digest_mismatch' if fault == 'source_changed' else 'office_output_must_be_new'
                assert str(error) == expected_code
            else:
                raise AssertionError('Race was reported as success: ' + fault)
        finally:
            engine.office_document = original_session
        if fault == 'source_changed':
            assert not race_output.exists()
        else:
            assert race_output.read_bytes() == b'concurrently-published-version'
        faults.append(fault)
    legacy = {}
    for extension in ('docx', 'pptx'):
        file = ROOT / 'qa/p0-completion-20261009/samples' / ('rich-source.' + extension)
        original_digest = digest(file)
        inspect_package(file, extension, original_digest)
        target = folder / ('legacy-' + extension + '.pdf')
        export(file, extension, target)
        legacy[extension] = {'pages': validate_pdf(target), 'sha256': digest(target)}
        assert digest(file) == original_digest
    assert digest(source) == original_sha and digest(edited) == edited_sha
    report = {'status': 'PASS', 'rendererVersion': version(), 'recalculationEvidence': evidence,
              'pdfPages': pages, 'dateDisplay': date_display, 'chartCache': cached,
              'publicationFaults': faults, 'legacyRealRendering': legacy,
              'checks': ['source preserved', 'MaterialEditor constant edit', '7 formula caches independently checked',
                         'hidden sheet', 'native reopened values', 'date type/display', 'literal 001', 'chart and refreshed cache',
                         'actual PDF values', 'actual engine formula error blocks publication', 'corrupt saved cache rejected',
                         'source change prevents publication', 'raced output not overwritten', 'DOCX/PPT real legacy exports'],
              'containerIsolation': 'NOT_RUN', 'crossSoftware': 'NOT_RUN', 'appE2E': 'NOT_RUN',
              'artifacts': {p.name: digest(p) for p in folder.glob('*.xlsx')}, 'pdfSha256': digest(pdf)}
    (folder / 'ENGINE_RESULTS.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-dir', required=True)
    parser.add_argument('--office-program', default=r'C:\Program Files\LibreOffice\program')
    parser.add_argument('--engine-child', action='store_true')
    args = parser.parse_args()
    folder = Path(args.run_dir).resolve()
    if args.engine_child:
        engine_child(folder)
        return
    folder.mkdir(parents=True, exist_ok=False)
    date = make_source(folder / 'source.xlsx')
    (folder / 'fixture.json').write_text(json.dumps({'dateSerial': date}))
    main = folder / 'Edit.kt'
    main.write_text('''import java.io.File
import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.material.MaterialEditor
fun main(args: Array<String>) {
    val source = File(args[0]); val output = File(args[1])
    val before = source.readBytes()
    val spec = Json.parseToJsonElement("""{"sheet":"Data","changes":[{"cell":"A2","expected":2,"value":7}]}""").jsonObject
    MaterialEditor.setCells(source, output, spec)
    check(source.readBytes().contentEquals(before))
    val inspected = MaterialEditor.excelCells(output, "Data", 0, 20)["cells"]!!.jsonArray
    check(inspected.single { it.jsonObject["cell"]!!.jsonPrimitive.content == "A2" }.jsonObject["value"] == JsonPrimitive(7))
    println("MaterialEditor actual source preserved and A2=7 read back")
}
''', encoding='utf-8')
    library = ROOT / '.local/gradle-9.6.0/lib'
    jars = [library / name for name in ['kotlin-stdlib-2.3.21.jar', 'kotlinx-coroutines-core-jvm-1.10.2.jar',
                                      'kotlinx-serialization-core-jvm-1.11.0.jar', 'kotlinx-serialization-json-jvm-1.11.0.jar', 'annotations-24.0.1.jar']]
    pdf_jar = next(p for p in (ROOT / 'client/document/build').rglob('*.jar')
                   if 'com/artifex/mupdf/fitz/PDFDocument.class' in ZipFile(p).namelist())
    jars.append(pdf_jar)
    cp = os.pathsep.join(str(p) for p in jars)
    materials = ROOT / 'client/app/src/main/java/me/rerere/rikkahub/feature/material'
    sources = [materials / (name + '.kt') for name in ['MaterialEditor', 'SafeOfficeXml', 'OfficePackage', 'PresentationSlideOrder']]
    classes = folder / 'classes'
    command(folder / 'kotlin-compile.log', ['java', '-cp', str(library / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                                          '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', classes, *sources, main])
    command(folder / 'kotlin-edit.log', ['java', '-cp', str(classes) + os.pathsep + cp, 'EditKt', folder / 'source.xlsx', folder / 'edited.xlsx'])
    original, edited = parts(folder / 'source.xlsx'), parts(folder / 'edited.xlsx')
    assert original.keys() == edited.keys()
    changed = [key for key in original if original[key] != edited[key]]
    assert set(changed) <= {'xl/workbook.xml', 'xl/worksheets/sheet1.xml', 'xl/worksheets/summary9.xml'}, changed
    assert original['xl/drawings/drawing1.xml'] == edited['xl/drawings/drawing1.xml']
    assert original['xl/charts/chart1.xml'] == edited['xl/charts/chart1.xml']
    assert original['xl/styles.xml'] == edited['xl/styles.xml']
    office = Path(args.office_program)
    env = os.environ.copy()
    env['PATH'] = str(office) + os.pathsep + env['PATH']
    import pypdf
    env['PYTHONPATH'] = os.pathsep.join([str(SERVICE), str(Path(pypdf.__file__).parents[1])])
    command(folder / 'real-engine.log', [office / 'python.exe', Path(__file__), '--engine-child', '--run-dir', folder], env)
    inputs = sources + jars + [Path(__file__), ROOT / 'qa/p0-completion-20261009/samples/rich-source.xlsx'] + list((SERVICE / 'office_worker').glob('*.py'))
    (folder / 'SOURCE_INPUTS.json').write_text(json.dumps({str(p): digest(p) for p in inputs}, indent=2), encoding='utf-8')
    print((folder / 'ENGINE_RESULTS.json').read_text())


if __name__ == '__main__':
    main()
