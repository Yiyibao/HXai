import hashlib
import json
from zipfile import ZipFile

import pytest
from api.recalculation_output import validate_recalculation_output
from office_worker.contracts import RECALCULATION_OPTIONS, XLSX_MEDIA_TYPE
from office_worker.recalculation import asset, verify_output


def fixture(tmp_path):
    output = tmp_path / 'output'
    output.mkdir()
    book = output / 'recalculated.xlsx'
    x = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
    r = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
    with ZipFile(book, 'w') as archive:
        archive.writestr('[Content_Types].xml', '<Types/>')
        archive.writestr('xl/workbook.xml', f'<workbook xmlns="{x}" xmlns:r="{r}"><sheets><sheet name="Data" r:id="s1"/></sheets></workbook>')
        archive.writestr('xl/_rels/workbook.xml.rels', f'<Relationships><Relationship Id="s1" Type="{r}/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
        archive.writestr('xl/worksheets/sheet1.xml', f'<worksheet xmlns="{x}"><sheetData><row r="2"><c r="B2"><f>A2*3</f><v>21</v></c></row></sheetData></worksheet>')
    witness = {'contractVersion': 1, 'sourceSha256': 'a' * 64, 'outputSha256': hashlib.sha256(book.read_bytes()).hexdigest(),
               'sheetNames': ['Data'], 'cells': [{'sheet': 'Data', 'cell': 'B2', 'number': 21, 'text': '21'}]}
    (output / 'calculation.json').write_text(json.dumps(witness))
    manifest = {'contractVersion': 1, 'operation': 'xlsx_recalculate', 'sourceSha256': 'a' * 64,
                'sourceExtension': 'xlsx', 'renderer': 'LibreOffice', 'rendererVersion': 'fixture-1',
                'renderOptions': RECALCULATION_OPTIONS, 'warnings': [],
                'recalculation': {'status': 'VERIFIED', 'scope': 'saved_formula_caches', 'formulaCount': 1, 'sheetCount': 1,
                                  'numericRelativeTolerance': 1e-12, 'numericAbsoluteTolerance': 1e-15, 'layout': 'NOT_VERIFIED'},
                'assets': [asset(book, 'xlsx', XLSX_MEDIA_TYPE, 30 * 1024 * 1024),
                           asset(output / 'calculation.json', 'calculation', 'application/json', 4 * 1024 * 1024)]}
    options = {'operation': 'xlsx_recalculate', 'sourceSha256': 'a' * 64, 'extension': 'xlsx',
               'rendererVersion': 'fixture-1', 'renderOptions': RECALCULATION_OPTIONS}
    (output / 'manifest.json').write_text(json.dumps(manifest))
    return output, manifest, witness, options


def test_fixed_workbook_and_witness_publish_with_independent_readback(tmp_path):
    output, manifest, _, options = fixture(tmp_path)
    assert validate_recalculation_output(tmp_path, options) == manifest
    assert (tmp_path / 'published/recalculated.xlsx').read_bytes() == (output / 'recalculated.xlsx').read_bytes()
    assert (tmp_path / 'published/calculation.json').read_bytes() == (output / 'calculation.json').read_bytes()


@pytest.mark.parametrize('mutation', ['source', 'renderer', 'operation', 'scope', 'path', 'digest', 'status', 'tolerance'])
def test_wrong_binding_asset_or_scope_never_publishes(tmp_path, mutation):
    output, manifest, _, options = fixture(tmp_path)
    if mutation == 'source': options['sourceSha256'] = 'b' * 64
    elif mutation == 'renderer': options['rendererVersion'] = 'wrong'
    elif mutation == 'operation': options['operation'] = 'office_pdf'
    elif mutation == 'scope': manifest['recalculation']['scope'] = 'all_math_proved'
    elif mutation == 'path': manifest['assets'][0]['path'] = '../outside.xlsx'
    elif mutation == 'digest': manifest['assets'][0]['sha256'] = 'b' * 64
    elif mutation == 'status': manifest['recalculation']['status'] = 'UNSUPPORTED'
    else: manifest['recalculation']['numericRelativeTolerance'] = 1.0
    (output / 'manifest.json').write_text(json.dumps(manifest))
    with pytest.raises(ValueError): validate_recalculation_output(tmp_path, options)
    assert not (tmp_path / 'published').exists()


@pytest.mark.parametrize('mutation', ['cached_value', 'duplicate', 'missing', 'wrong_sheet', 'type'])
def test_rehashed_witness_cannot_hide_wrong_saved_result(tmp_path, mutation):
    output, manifest, witness, options = fixture(tmp_path)
    if mutation == 'cached_value': witness['cells'][0]['number'] = 6
    elif mutation == 'duplicate': witness['cells'].append(witness['cells'][0].copy())
    elif mutation == 'missing': witness['cells'] = []
    elif mutation == 'wrong_sheet': witness['sheetNames'] = ['Wrong']; witness['cells'][0]['sheet'] = 'Wrong'
    else: witness['cells'][0]['number'] = None
    (output / 'calculation.json').write_text(json.dumps(witness))
    manifest['assets'][1] = asset(output / 'calculation.json', 'calculation', 'application/json', 4 * 1024 * 1024)
    (output / 'manifest.json').write_text(json.dumps(manifest))
    with pytest.raises(ValueError): verify_output(output)
    assert not (tmp_path / 'published').exists()
