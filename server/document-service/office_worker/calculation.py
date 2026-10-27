"""Independent OOXML readback of LibreOffice's saved formula results, not a proof engine."""
import hashlib
import math
import re
from zipfile import ZipFile

from office_worker.package import inspect_package, parse_office_xml

X = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'


def calculate(document):
    # Bound the explicit calculateAll call, including hidden sheets.
    selected = []
    count = 0
    for sheet in document.getSheets():
        for address in sheet.queryContentCells(16).getRangeAddresses():
            count += (address.EndRow - address.StartRow + 1) * (address.EndColumn - address.StartColumn + 1)
            if count > 100000:
                raise ValueError('office_formula_limit')
            selected.append((sheet, address))
    document.calculateAll()
    expected = {}
    for sheet, address in selected:
        for row in range(address.StartRow, address.EndRow + 1):
            for col in range(address.StartColumn, address.EndColumn + 1):
                cell = sheet.getCellByPosition(col, row)
                if cell.getError():
                    raise ValueError('formula_recalculation_failed')
                kind = cell.getPropertyValue('FormulaResultType').value
                if kind not in ('VALUE', 'TEXT'):
                    raise ValueError('formula_recalculation_failed')
                number = cell.getValue() if kind == 'VALUE' else None
                if number is not None and not math.isfinite(number):
                    raise ValueError('formula_recalculation_failed')
                expected[(sheet.getName(), col, row)] = (number, cell.getString())
    return expected


def verify_saved(path, expected, sheet_names=None):
    if path.is_symlink() or not path.is_file() or not 1 <= path.stat().st_size <= 30 * 1024 * 1024:
        raise ValueError('invalid_office_input')
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    inspect_package(path, 'xlsx', digest)
    found = set()
    with ZipFile(path) as archive:
        workbook = parse_office_xml(archive.read('xl/workbook.xml'))
        relations = parse_office_xml(archive.read('xl/_rels/workbook.xml.rels'))
        by_id = {}
        for relation in relations:
            key = relation.get('Id')
            if not key or key in by_id:
                raise ValueError('invalid_worksheet_relationship')
            by_id[key] = relation
        names = set()
        for sheet in workbook.findall(f'{{{X}}}sheets/{{{X}}}sheet'):
            name = sheet.get('name')
            if not name or name in names:
                raise ValueError('invalid_worksheet_relationship')
            names.add(name)
            relation = by_id.get(sheet.get(f'{{{R}}}id'))
            if relation is None or relation.get('Type') != R + '/worksheet' or relation.get('TargetMode', 'Internal') != 'Internal':
                raise ValueError('invalid_worksheet_relationship')
            target = relation.get('Target', '').removeprefix('/xl/')
            if not re.fullmatch(r'worksheets/[A-Za-z0-9_.-]+\.xml', target):
                raise ValueError('invalid_worksheet_relationship')
            root = parse_office_xml(archive.read('xl/' + target))
            for cell in root.iter(f'{{{X}}}c'):
                if cell.find(f'{{{X}}}f') is None:
                    continue
                address = cell.get('r', '')
                if not re.fullmatch(r'[A-Z]{1,3}[1-9][0-9]{0,6}', address):
                    raise ValueError('invalid_formula_cell')
                column = 0
                for char in address.rstrip('0123456789'):
                    column = column * 26 + ord(char) - 64
                row = int(address.lstrip('ABCDEFGHIJKLMNOPQRSTUVWXYZ'))
                if column > 16384 or row > 1048576:
                    raise ValueError('invalid_formula_cell')
                key = (name, column - 1, row - 1)
                if key in found or key not in expected:
                    raise ValueError('formula_readback_mismatch')
                found.add(key)
                cache = cell.find(f'{{{X}}}v')
                if cache is None:
                    raise ValueError('formula_cache_missing')
                number, text = expected[key]
                kind = cell.get('t', 'n')
                if kind == 'str':
                    matches = number is None and (cache.text or '') == text
                elif kind in ('n', 'b') and number is not None:
                    try:
                        value = float(cache.text)
                    except (ValueError, TypeError):
                        raise ValueError('formula_readback_mismatch') from None
                    # XLSX decimal serialization can round the engine's binary double.
                    matches = math.isfinite(value) and math.isclose(value, number, rel_tol=1e-12, abs_tol=1e-15)
                    if kind == 'b':
                        matches = matches and cache.text in ('0', '1')
                else:
                    matches = False
                if not matches:
                    raise ValueError('formula_readback_mismatch')
    if found != expected.keys():
        raise ValueError('formula_readback_mismatch')
    if sheet_names is not None and names != set(sheet_names):
        raise ValueError('recalculation_sheet_mismatch')
    return {'sha256': digest, 'formulaCount': len(found), 'sheetCount': len(names),
            'scope': 'saved_formula_caches', 'numericRelativeTolerance': 1e-12,
            'numericAbsoluteTolerance': 1e-15}
