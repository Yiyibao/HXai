from zipfile import ZipFile

import pytest
from office_worker.calculation import verify_saved
from office_worker.engine import recalculate_xlsx

X = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'


def saved(tmp_path, cell='<c r="B2"><f>A2*3</f><v>21</v></c>', target='worksheets/logical9.xml', extra=''):
    path = tmp_path / 'saved.xlsx'
    with ZipFile(path, 'w') as archive:
        archive.writestr('[Content_Types].xml', '<Types/>')
        archive.writestr('xl/workbook.xml', f'<workbook xmlns="{X}" xmlns:r="{R}"><sheets><sheet name="Hidden" state="hidden" r:id="s1"/>{extra}</sheets></workbook>')
        archive.writestr('xl/_rels/workbook.xml.rels', f'<Relationships><Relationship Id="s1" Type="{R}/worksheet" Target="{target}"/></Relationships>')
        archive.writestr('xl/worksheets/logical9.xml', f'<worksheet xmlns="{X}"><sheetData><row r="2">{cell}</row></sheetData></worksheet>')
    return path


def test_saved_hidden_sheet_uses_relationship_not_filename_order(tmp_path):
    evidence = verify_saved(saved(tmp_path), {('Hidden', 1, 1): (21.0, '21')})
    assert evidence['formulaCount'] == 1 and evidence['sheetCount'] == 1


@pytest.mark.parametrize('cell', [
    '<c r="B2"><f>A2*3</f></c>',
    '<c r="B2"><f>A2*3</f><v/></c>',
    '<c r="B2"><f>A2*3</f><v>6</v></c>',
    '<c r="B2"><f>A2*3</f><v>NaN</v></c>',
    '<c r="B2" t="e"><f>A2*3</f><v>#VALUE!</v></c>',
    '<c r="B2" t="b"><f>A2*3</f><v>21</v></c>',
    '<c r="B2" t="str"><f>A2*3</f><v>21</v></c>',
    '<c r="B2"><v>21</v></c>',
    '<c r="C2"><f>A2*3</f><v>21</v></c>',
    '<c r="B2"><f>A2*3</f><v>21</v></c><c r="B2"><f>A2*3</f><v>21</v></c>',
])
def test_missing_stale_wrong_error_or_duplicate_formula_caches_are_rejected(tmp_path, cell):
    with pytest.raises(ValueError):
        verify_saved(saved(tmp_path, cell), {('Hidden', 1, 1): (21.0, '21')})


@pytest.mark.parametrize('kind,value,expected', [
    ('str', 'sum=22', (None, 'sum=22')),
    ('str', '', (None, '')),
    ('b', '1', (1.0, 'TRUE')),
    ('n', '0.3', (0.30000000000000004, '0.3')),
])
def test_valid_text_empty_boolean_and_rounded_numeric_results_are_accepted(tmp_path, kind, value, expected):
    cell = f'<c r="B2" t="{kind}"><f>expression</f><v>{value}</v></c>'
    assert verify_saved(saved(tmp_path, cell), {('Hidden', 1, 1): expected})['formulaCount'] == 1


@pytest.mark.parametrize('target', ['../outside.xml', 'worksheets/missing.xml'])
def test_untrusted_or_missing_worksheet_target_cannot_validate(tmp_path, target):
    with pytest.raises((ValueError, KeyError)):
        verify_saved(saved(tmp_path, target=target), {('Hidden', 1, 1): (21.0, '21')})


def test_duplicate_sheet_name_cannot_hide_an_unverified_sheet(tmp_path):
    with pytest.raises(ValueError):
        verify_saved(saved(tmp_path, extra='<sheet name="Hidden" r:id="s1"/>'), {('Hidden', 1, 1): (21.0, '21')})


def test_expected_formula_cannot_disappear_from_saved_file(tmp_path):
    with pytest.raises(ValueError, match='formula_readback_mismatch'):
        verify_saved(saved(tmp_path), {('Hidden', 1, 1): (21.0, '21'), ('Other', 0, 0): (5.0, '5')})


def test_recalculation_refuses_to_replace_source_or_existing_output(tmp_path):
    source = saved(tmp_path)
    original = source.read_bytes()
    with pytest.raises(ValueError, match='office_output_must_be_new'):
        recalculate_xlsx(source, source, '0' * 64)
    output = tmp_path / 'output.xlsx'
    output.write_bytes(b'previous-version')
    with pytest.raises(ValueError, match='office_output_must_be_new'):
        recalculate_xlsx(source, output, '0' * 64)
    assert source.read_bytes() == original and output.read_bytes() == b'previous-version'


def test_wrong_source_hash_rejects_before_engine_and_does_not_publish(tmp_path):
    source = saved(tmp_path)
    original = source.read_bytes()
    output = tmp_path / 'output.xlsx'
    with pytest.raises(ValueError, match='source_digest_mismatch'):
        recalculate_xlsx(source, output, '0' * 64)
    assert source.read_bytes() == original and not output.exists()


def test_formula_limit_prevents_explicit_recalculation():
    from types import SimpleNamespace
    from office_worker.calculation import calculate
    address = SimpleNamespace(StartRow=0, EndRow=100000, StartColumn=0, EndColumn=0)
    sheet = SimpleNamespace(queryContentCells=lambda _flags: SimpleNamespace(getRangeAddresses=lambda: [address]))
    class Document:
        calculated = False
        def getSheets(self):
            return [sheet]
        def calculateAll(self):
            self.calculated = True
    document = Document()
    with pytest.raises(ValueError, match='office_formula_limit'):
        calculate(document)
    assert document.calculated is False
