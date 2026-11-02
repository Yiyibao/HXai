import importlib.util
import json
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_b_microsoft_office as office
import office_roundtrip_contract as structural
import verify_b_office_compatibility as compatibility

FIXTURE = ROOT / 'HXai_CHANGE_REVIEW/B27-B-LINE-CLOSURE-20261027/microsoft-office-final'
M = '{http://schemas.openxmlformats.org/officeDocument/2006/math}'
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'


@pytest.mark.parametrize('damage', ['superscript_becomes_subscript', 'non_target_paragraph_deleted'])
def test_saved_word_same_text_math_or_pdf_cannot_hide_semantic_damage(tmp_path, damage):
    # Exact synthetic source and actual Office-saved output from B27; no engine runs.
    manifest = json.loads((FIXTURE / 'INPUTS.json').read_text(encoding='utf-8'))
    job = manifest['jobs'][0]
    shutil.copytree(FIXTURE / 'office', tmp_path / 'office')
    saved = tmp_path / 'office/word-source.docx'
    with ZipFile(saved) as z:
        parts = {n: z.read(n) for n in z.namelist()}
    root = ET.fromstring(parts['word/document.xml'])
    if damage == 'superscript_becomes_subscript':
        node = root.find('.//' + M + 'sSup')
        node.tag = M + 'sSub'
        node.find(M + 'sup').tag = M + 'sub'
    else:
        body = root.find(W + 'body')
        paragraph = next(p for p in body.findall(W + 'p') if 'Representative source A: hypotheses remain unchanged.' in ''.join(p.itertext()))
        body.remove(paragraph)
    parts['word/document.xml'] = ET.tostring(root)
    # This is a synthetic adversarial output; update its declared digest to test
    # structural verification rather than a trivial stale manifest hash.
    saved.unlink()
    with ZipFile(saved, 'x', ZIP_DEFLATED) as z:
        for name, raw in parts.items():
            z.writestr(name, raw)
    record = json.loads((tmp_path / 'office/OFFICE_RUN.json').read_text(encoding='utf-8-sig'))
    record['jobs'] = [record['jobs'][0]]
    record['jobs'][0]['outputSha256'] = office.digest(saved)
    (tmp_path / 'office/OFFICE_RUN.json').write_text(json.dumps(record), encoding='utf-8')
    with pytest.raises(ValueError, match='word_.*changed'):
        office.check_outputs(tmp_path, [job], manifest['dateSerial'])


def test_actual_saved_reference_keeps_supported_word_math_and_non_target_content():
    for label in ('word-source', 'word-edited', 'word-notes'):
        result = structural.word_roundtrip(structural.package(FIXTURE / f'inputs/{label}.docx', 'docx'),
                                          structural.package(FIXTURE / f'office/{label}.docx', 'docx'))
        assert result['allStoryParagraphsPreserved']
        assert result['mathStructuresPreserved'] > 0


def test_normal_utf16_and_font_properties_do_not_change_math_but_dtd_and_xinclude_are_rejected():
    source = f'<m:oMath xmlns:m="{M[1:-1]}"><m:r><m:t>x</m:t></m:r></m:oMath>'
    formatted = f'<m:oMath xmlns:m="{M[1:-1]}"><m:r><m:rPr><m:sty m:val="p"/></m:rPr><m:t>x</m:t></m:r></m:oMath>'
    assert structural.math_tree(structural.parse_office_xml(source.encode('utf-16'))) == structural.math_tree(structural.parse_office_xml(formatted.encode()))
    for text in ('<!DOCTYPE x [<!ENTITY x "bad">]><x/>', '<x xmlns:i="http://www.w3.org/2001/xi&#110;clude"><i:include href="file:///never-read"/></x>'):
        with pytest.raises(ValueError):
            structural.parse_office_xml(text.encode('utf-16'))


def test_offline_compatibility_bundle_and_readback_do_not_attest_engine(tmp_path):
    bundle = tmp_path / 'bundle'
    compatibility.prepare(FIXTURE / 'INPUTS.json', FIXTURE / 'office', bundle)
    seal = json.loads((bundle / 'BUNDLE.json').read_text(encoding='utf-8'))
    record = {'contractVersion': 1, 'engine': 'Microsoft Office', 'engineVersion': 'historical B27 reference only',
              'origin': 'EXTERNAL_UNATTESTED', 'bundleSha256': compatibility.sha(bundle / 'BUNDLE.json'), 'jobs': []}
    for job in seal['jobs']:
        label, kind = job['label'], job['kind']
        record['jobs'].append({'label': label, 'saved': f'{label}.{kind}', 'pdf': f'{label}.pdf',
            'savedSha256': compatibility.sha(FIXTURE / f'office/{label}.{kind}'),
            'pdfSha256': compatibility.sha(FIXTURE / f'office/{label}.pdf'),
            'operations': ['OPEN', 'SAVE_AS', 'REOPEN', 'EXPORT_PDF', 'FULL_RECALCULATE']})
    record_path = tmp_path / 'engine.json';record_path.write_text(json.dumps(record), encoding='utf-8')
    report = tmp_path / 'report.json'
    result = compatibility.verify(bundle, FIXTURE / 'office', record_path, report)
    assert result['jobs'] == 5 and result['engineExecution'] == 'EXTERNAL_UNATTESTED'
    assert json.loads(report.read_text(encoding='utf-8'))['compatibilityQualification'] == 'NOT_EVALUATED'
    record['jobs'].pop();record_path.write_text(json.dumps(record), encoding='utf-8')
    with pytest.raises(ValueError, match='incomplete_output_coverage'):
        compatibility.verify(bundle, FIXTURE / 'office', record_path, tmp_path / 'missing.json')


def test_logical_presentation_order_and_hidden_flags_match_after_save():
    before = structural.presentation(structural.package(FIXTURE / 'inputs/presentation.pptx', 'pptx'))
    after = structural.presentation(structural.package(FIXTURE / 'office/powerpoint-logical.pptx', 'pptx'))
    assert before == after and len(before) == 5
    assert sum(s['hidden'] for s in before) == 2


def test_formula_deletion_is_rejected_even_when_cached_value_is_unchanged():
    before = structural.package(FIXTURE / 'office/excel-rich.xlsx', 'xlsx')
    after = dict(before)
    part = next(name for name in after if name.startswith('xl/worksheets/') and
                any(c.get('r') == 'B2' and c.find(structural.X + 'f') is not None for c in structural.parse_office_xml(after[name]).iter(structural.X + 'c')))
    xml = structural.parse_office_xml(after[part])
    cell = next(c for c in xml.iter(structural.X + 'c') if c.get('r') == 'B2')
    cell.remove(cell.find(structural.X + 'f'));after[part] = ET.tostring(xml)
    with pytest.raises(ValueError, match='^workbook_formula_changed$'):
        structural.xlsx_roundtrip(before, after)


def test_significant_whitespace_is_preserved_and_only_unmarked_space_is_ignorable():
    assert structural.paragraph(ET.fromstring(f'<w:p xmlns:w="{W[1:-1]}"><w:r><w:t xml:space="preserve"> x </w:t></w:r></w:p>')) != \
           structural.paragraph(ET.fromstring(f'<w:p xmlns:w="{W[1:-1]}"><w:r><w:t>x</w:t></w:r></w:p>'))


def test_table_cell_move_cannot_hide_behind_identical_flat_paragraphs():
    before = structural.package(FIXTURE / 'office/word-notes.docx', 'docx')
    after = dict(before);xml = structural.parse_office_xml(after['word/document.xml'])
    row = next(xml.iter(W + 'tr'));cells = row.findall(W + 'tc')
    moved = cells[1].find(W + 'p');cells[1].remove(moved);cells[0].append(moved)
    after['word/document.xml'] = ET.tostring(xml)
    with pytest.raises(ValueError, match='^word_table_structure_changed$'):
        structural.word_roundtrip(before, after)


@pytest.mark.parametrize('kind', ['n', 'inlineStr'])
def test_original_blank_styled_cell_cannot_acquire_unrequested_content(kind):
    before = structural.package(FIXTURE / 'office/excel-rich.xlsx', 'xlsx')
    part = next(name for name in before if name.startswith('xl/worksheets/') and name.endswith('.xml'))
    xml = structural.parse_office_xml(before[part])
    row = next(xml.iter(structural.X + 'row'))
    blank = ET.SubElement(row, structural.X + 'c', {'r': 'ZZ' + row.get('r'), 's': '0'})
    before[part] = ET.tostring(xml)
    assert structural.xlsx_roundtrip(before, before)['originalCellsAndFormulasPreserved']
    blank.set('t', kind)
    if kind == 'n':
        ET.SubElement(blank, structural.X + 'v').text = '99'
    else:
        ET.SubElement(ET.SubElement(blank, structural.X + 'is'), structural.X + 't').text = 'UNREQUESTED'
    after = {**before, part: ET.tostring(xml)}
    with pytest.raises(ValueError, match='^workbook_unexpected_content$'):
        structural.xlsx_roundtrip(before, after)
