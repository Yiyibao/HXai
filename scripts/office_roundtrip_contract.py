"""Independent, bounded structural readback for an unchanged OOXML save/reopen.

Formatting equivalence is intentionally limited. This does not certify arbitrary
Office layout, physical recalculation, engine identity or unsupported content.
"""
from decimal import Decimal, InvalidOperation
import hashlib
from pathlib import Path
import posixpath
import re
import sys
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server/document-service'))
from office_worker.package import inspect_package, parse_office_xml

M = '{http://schemas.openxmlformats.org/officeDocument/2006/math}'
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
A = '{http://schemas.openxmlformats.org/drawingml/2006/main}'
P = '{http://schemas.openxmlformats.org/presentationml/2006/main}'
X = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'
R = '{http://schemas.openxmlformats.org/officeDocument/2006/relationships}'


def require(condition, code):
    if not condition:
        raise ValueError(code)


def package(path, kind):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), 'invalid_office_file')
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    inspect_package(path, kind, digest)
    with ZipFile(path) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


def math_tree(node):
    # Office commonly inserts run/font/control properties. They have no algebraic
    # role here; retain fraction/script/operator/argument structure and math values.
    if node.tag in (M + 'rPr', M + 'ctrlPr', W + 'rPr'):
        return None
    children = [value for child in node if (value := math_tree(child)) is not None]
    attrs = tuple(sorted((key, value) for key, value in node.attrib.items() if key.startswith(M)))
    if node.tag.endswith('Pr') and not children and not attrs:
        return None
    if node.tag == M + 'r' and children and all(c[0] == M + 't' for c in children):
        return ('MATH_TEXT', ''.join(c[2] for c in children))
    merged = []
    for child in children:
        if merged and child[0] == merged[-1][0] == 'MATH_TEXT':
            merged[-1] = ('MATH_TEXT', merged[-1][1] + child[1])
        else:
            merged.append(child)
    return (node.tag, attrs, node.text or '' if node.tag in (M + 't', W + 't') else '', tuple(merged))


def paragraph(node):
    tokens = []
    def visit(element):
        if element.tag == M + 'oMath':
            tokens.append(('math', math_tree(element)))
        elif element.tag == W + 'fldSimple':
            tokens.extend([('field_begin', None), ('field_instruction', element.get(W + 'instr', '').strip()), ('field_separate', None)])
            for child in element:
                visit(child)
            tokens.append(('field_end', None))
        elif element.tag == W + 'fldChar':
            kind = element.get(W + 'fldCharType')
            require(kind in ('begin', 'separate', 'end'), 'unsupported_word_field')
            tokens.append(('field_' + kind, None))
        elif element.tag in (W + 't', W + 'instrText'):
            value = element.text or ''
            kind = 'field_instruction' if element.tag == W + 'instrText' else 'text'
            if kind == 'text' and element.get('{http://www.w3.org/XML/1998/namespace}space') != 'preserve':
                value = value.strip(' \t\r\n')
            if tokens and tokens[-1][0] == kind:
                tokens[-1] = (kind, tokens[-1][1] + value)
            else:
                tokens.append((kind, value))
        elif element.tag in (W + 'tab', W + 'br', W + 'cr', W + 'noBreakHyphen', W + 'softHyphen'):
            tokens.append((element.tag, element.get(W + 'type')))
        else:
            for child in element:
                visit(child)
    visit(node)
    return [(kind, value.strip() if kind == 'field_instruction' else value) for kind, value in tokens]


def word_roundtrip(source, saved):
    def stories(parts):
        result = {'document': parse_office_xml(parts['word/document.xml'])}
        rels = relationships(parts, 'word/_rels/document.xml.rels', 'word')
        for section, node in enumerate(result['document'].iter(W + 'sectPr')):
            for reference in node:
                if reference.tag in (W + 'headerReference', W + 'footerReference'):
                    identity = reference.get(R + 'id')
                    require(identity in rels, 'word_story_reference_changed')
                    key = (reference.tag, section, reference.get(W + 'type', 'default'))
                    require(key not in result, 'word_story_reference_changed')
                    result[key] = parse_office_xml(parts[rels[identity]])
        # Word adds empty separator/continuation stories on save. Only actual
        # footnote/endnote content is compared; auto-added separators are structural defaults.
        for kind in ('footnote', 'endnote'):
            name = f'word/{kind}s.xml'
            if name in parts:
                for note in parse_office_xml(parts[name]):
                    if note.get(W + 'type') not in ('separator', 'continuationSeparator', 'continuationNotice'):
                        result[(kind, note.get(W + 'id'))] = note
        return result
    original, written = stories(source), stories(saved)
    require(original.keys() == written.keys(), 'word_story_parts_changed')
    count = 0
    for name in original:
        before, after = original[name], written[name]
        left = [math_tree(m) for m in before.iter(M + 'oMath')]
        right = [math_tree(m) for m in after.iter(M + 'oMath')]
        require(left == right, 'word_math_structure_changed')
        left_p = [paragraph(p) for p in before.iter(W + 'p')]
        right_p = [paragraph(p) for p in after.iter(W + 'p')]
        if name == 'document':
            left_body = [n for n in before.find(W + 'body') if n.tag != W + 'sectPr']
            right_body = [n for n in after.find(W + 'body') if n.tag != W + 'sectPr']
            # Word materializes its mandatory empty terminal paragraph after a
            # body-ending table. Do not ignore arbitrary inserted/removed blanks.
            if left_body and left_body[-1].tag == W + 'tbl' and len(right_body) >= 2 and right_body[-2].tag == W + 'tbl' and right_body[-1].tag == W + 'p' and right_p[-1] == []:
                right_p = right_p[:-1]
        require(left_p == right_p, 'word_non_target_content_changed')
        def tables(root):
            return [[[{'span': cell.find(W + 'tcPr/' + W + 'gridSpan').get(W + 'val', '1')
                       if cell.find(W + 'tcPr/' + W + 'gridSpan') is not None else '1',
                       'merge': cell.find(W + 'tcPr/' + W + 'vMerge').get(W + 'val', 'continue')
                       if cell.find(W + 'tcPr/' + W + 'vMerge') is not None else None,
                       'paragraphs': [paragraph(p) for p in cell.findall(W + 'p')]}
                      for cell in row.findall(W + 'tc')] for row in table.findall(W + 'tr')] for table in root.iter(W + 'tbl')]
        require(tables(before) == tables(after), 'word_table_structure_changed')
        count += len(left)
    return {'mathStructuresPreserved': count, 'allStoryParagraphsPreserved': True,
            'scope': 'OMML_STRUCTURE_PARAGRAPH_TEXT_FIELDS_BREAKS_TABLE_ROWS_CELLS_SPANS; not complete layout equivalence'}


def relationships(parts, name, directory):
    result = {}
    for rel in parse_office_xml(parts[name]):
        identity = rel.get('Id')
        require(identity and identity not in result, 'duplicate_office_relationship')
        if rel.get('TargetMode') == 'External':
            continue
        target = rel.get('Target', '')
        path = posixpath.normpath(target.lstrip('/') if target.startswith('/') else posixpath.join(directory, target))
        require(path in parts and not path.startswith('../'), 'invalid_office_relationship_target')
        result[identity] = path
    return result


def presentation(parts):
    order = parse_office_xml(parts['ppt/presentation.xml']).find(P + 'sldIdLst')
    require(order is not None, 'missing_slide_order')
    rels = relationships(parts, 'ppt/_rels/presentation.xml.rels', 'ppt')
    seen, slides = set(), []
    for item in order:
        identity = item.get(R + 'id'); require(identity in rels and identity not in seen, 'invalid_logical_slide')
        seen.add(identity)
        slide = parse_office_xml(parts[rels[identity]])
        def paragraph_content(p):
            tokens = []
            for node in p:
                if node.tag == A + 'fld':
                    # A saved slide number/date field may update its cached text.
                    tokens.append(('FIELD', node.get('type')))
                else:
                    value = ''.join(t.text or '' for t in node.iter(A + 't'))
                    if value:
                        if tokens and tokens[-1][0] == 'TEXT':
                            tokens[-1] = ('TEXT', tokens[-1][1] + value)
                        else:
                            tokens.append(('TEXT', value))
            return tokens
        slides.append({'hidden': slide.get('show', '1') in ('0', 'false'),
                       'paragraphs': [paragraph_content(p) for p in slide.iter(A + 'p')],
                       'shapes': sum(1 for n in slide.iter() if n.tag in (P + 'sp', P + 'pic', P + 'graphicFrame'))})
    return slides


def workbook(parts):
    book = parse_office_xml(parts['xl/workbook.xml'])
    rels = relationships(parts, 'xl/_rels/workbook.xml.rels', 'xl')
    shared = []
    if 'xl/sharedStrings.xml' in parts:
        shared = [''.join(t.text or '' for t in item.iter(X + 't')) for item in parse_office_xml(parts['xl/sharedStrings.xml'])]
    styles = parse_office_xml(parts['xl/styles.xml'])
    formats = {int(n.get('numFmtId')): n.get('formatCode') for n in styles.iter(X + 'numFmt')}
    xfs = list(styles.find(X + 'cellXfs'))
    result = []
    for sheet in book.find(X + 'sheets'):
        cells = {}
        for cell in parse_office_xml(parts[rels[sheet.get(R + 'id')]]).iter(X + 'c'):
            address = cell.get('r');require(address and address not in cells, 'duplicate_workbook_cell')
            kind = cell.get('t', 'n'); value = cell.findtext(X + 'v')
            if kind == 's':
                value = shared[int(value)];kind = 'text'
            elif kind == 'inlineStr':
                value = ''.join(t.text or '' for t in cell.iter(X + 't'));kind = 'text'
            elif kind == 'str':
                value = value or '';kind = 'text'
            elif kind == 'n' and value is not None:
                if value == '':
                    value = None  # Missing formula cache is unknown, never a numeric zero.
                else:
                    try:
                        value = Decimal(value)
                    except InvalidOperation:
                        raise ValueError('invalid_numeric_cell') from None
                    require(value.is_finite(), 'nonfinite_cell')
            style = xfs[int(cell.get('s', '0'))]
            fmt_id = int(style.get('numFmtId', '0'))
            fmt = formats.get(fmt_id, f'builtin:{fmt_id}')
            # Preserve date formatting independently of numeric serial/cache.
            date = fmt_id in set(range(14, 23)) | set(range(45, 48)) or any(token in fmt.lower() for token in ('yy', 'dd'))
            cells[address] = {'type': kind, 'value': value, 'formula': cell.findtext(X + 'f'), 'date': date}
        result.append({'name': sheet.get('name'), 'state': sheet.get('state', 'visible'), 'cells': cells})
    return result


def xlsx_roundtrip(source, saved):
    left, right = workbook(source), workbook(saved)
    require([(s['name'], s['state']) for s in left] == [(s['name'], s['state']) for s in right], 'workbook_sheets_changed')
    for before, after in zip(left, right, strict=True):
        # Engines may emit empty styled cells; compare every original substantive cell.
        for address, cell in before['cells'].items():
            if cell['value'] is None and cell['formula'] is None:
                actual = after['cells'].get(address)
                require(actual is None or actual['value'] is None and actual['formula'] is None,
                        'workbook_unexpected_content')
                continue
            actual = after['cells'].get(address)
            require(actual is not None and actual['formula'] == cell['formula'], 'workbook_formula_changed')
            require(actual['date'] == cell['date'], 'workbook_date_format_changed')
            if cell['formula'] is None:
                require(actual['value'] == cell['value'] and actual['type'] == cell['type'], 'workbook_non_formula_value_changed')
        for address, cell in after['cells'].items():
            require(address in before['cells'] or cell['value'] is None and cell['formula'] is None, 'workbook_unexpected_content')
    def charts(parts):
        c = '{http://schemas.openxmlformats.org/drawingml/2006/chart}'
        signatures = []
        for name, raw in parts.items():
            if not (name.startswith('xl/charts/') and name.endswith('.xml')):
                continue
            root = parse_office_xml(raw)
            titles = []
            for series in root.iter(c + 'ser'):
                title = series.find(c + 'tx')
                if title is None:
                    titles.append(None)
                    continue
                literal = title.find(c + 'v')
                reference = title.find(c + 'strRef')
                if literal is not None:
                    require(len(title) == 1, 'workbook_chart_references_changed')
                    titles.append(('literal', literal.text or ''))
                    continue
                # WPS represents an existing literal legend as a quoted constant
                # strRef. This is not a workbook dependency. Normalize only that
                # exact representation, with its single matching cache value.
                formula = reference.findtext(c + 'f') if reference is not None else None
                if formula is not None and re.fullmatch(r'"(?:[^"]|"")*"', formula):
                    value = formula[1:-1].replace('""', '"')
                    cache = reference.find(c + 'strCache')
                    points = [] if cache is None else cache.findall(c + 'pt')
                    count = None if cache is None else cache.find(c + 'ptCount')
                    require(len(title) == 1 and len(reference) == 2 and count is not None
                            and count.get('val') == '1' and len(points) == 1 and len(cache) == 2
                            and points[0].get('idx') == '0' and len(points[0]) == 1
                            and points[0].findtext(c + 'v') == value,
                            'workbook_chart_references_changed')
                    titles.append(('literal', value))
                    # Removing this constant from the parsed copy does not remove
                    # a real range reference or modify either Office file.
                    reference.remove(reference.find(c + 'f'))
                else:
                    titles.append(('reference', formula))
            references = tuple(n.text or '' for n in root.iter(c + 'f'))
            signatures.append((references, tuple(titles)))
        return sorted(signatures, key=repr)
    require(charts(source) == charts(saved), 'workbook_chart_references_changed')
    return {'originalCellsAndFormulasPreserved': True, 'dateFormattingRetained': True, 'chartReferencesPreserved': True,
            'physicalRecalculation': 'NOT_ATTESTED_BY_STRUCTURE'}
