"""B03 synthetic PPT/PDF fixtures and actual full-slide UNO/PDFium verification.
Does not normalize user uploads, install dependencies, start Docker or call models.
"""
import argparse
import copy
import ctypes
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

from verify_b_docx_omml import digest, configure_pdfium, render_pdf

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / 'server/document-service'
P = 'http://schemas.openxmlformats.org/presentationml/2006/main'
A = 'http://schemas.openxmlformats.org/drawingml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
PKG = 'http://schemas.openxmlformats.org/package/2006/relationships'
CT = 'http://schemas.openxmlformats.org/package/2006/content-types'
MC = 'http://schemas.openxmlformats.org/markup-compatibility/2006'
TITLES = ['HIDDEN FIRST', 'VISIBLE EDIT PAGE', 'VISIBLE COMPAT PAGE', 'HIDDEN MIDDLE', 'VISIBLE LAST']
PARTS = ['ppt/slides/slide2.xml', 'ppt/slides/slide9.xml', 'ppt/slides/slide1.xml', 'ppt/slides/slide7.xml', 'ppt/slides/slide4.xml']
HIDDEN = [True, False, False, True, False]
for prefix, namespace in [('p', P), ('a', A), ('r', R), ('mc', MC)]:
    ET.register_namespace(prefix, namespace)


def text_shape(identity, name, text, x, y, width, height, size=1800):
    shape = ET.fromstring(f'<p:sp xmlns:p="{P}" xmlns:a="{A}"><p:nvSpPr><p:cNvPr/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off/><a:ext/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr wrap="square"/><a:lstStyle/><a:p><a:r><a:rPr lang="en-US"/><a:t/></a:r></a:p></p:txBody></p:sp>')
    shape.find('.//{' + P + '}cNvPr').attrib.update(id=str(identity), name=name)
    shape.find('.//{' + A + '}off').attrib.update(x=str(x), y=str(y))
    shape.find('.//{' + A + '}ext').attrib.update(cx=str(width), cy=str(height))
    shape.find('.//{' + A + '}rPr').set('sz', str(size)); shape.find('.//{' + A + '}t').text = text
    return shape


def make_fixtures(folder, scan_source):
    base = ROOT / 'qa/p0-completion-20261009/samples/rich-source.pptx'
    with ZipFile(base) as archive:
        package = {name: archive.read(name) for name in archive.namelist()}
    template = ET.fromstring(package['ppt/slides/slide1.xml'])
    source_rels = ET.fromstring(package['ppt/slides/_rels/slide1.xml.rels'])
    for relation in list(source_rels):
        if relation.get('Type', '').rsplit('/', 1)[-1] not in {'slideLayout', 'image'}:
            source_rels.remove(relation)
    serialized_rels = ET.tostring(source_rels, encoding='utf-8', xml_declaration=True)
    for index, part in enumerate(PARTS):
        root = copy.deepcopy(template)
        if HIDDEN[index]: root.set('show', '0')
        else: root.attrib.pop('show', None)
        tree = root.find('{' + P + '}cSld/{' + P + '}spTree')
        for child in list(tree):
            if child.tag not in {'{' + P + '}nvGrpSpPr', '{' + P + '}grpSpPr', '{' + P + '}pic'}:
                tree.remove(child)
        tree.append(text_shape(2, 'Unique logical title', TITLES[index], 400000, 180000, 8300000, 700000, 2800))
        tree.append(text_shape(4, 'Repeated ordinary label', 'DUPLICATE', 4572000, 1828800, 3500000, 700000))
        group = ET.fromstring(f'<p:grpSp xmlns:p="{P}" xmlns:a="{A}"><p:nvGrpSpPr><p:cNvPr id="16" name="Preserved group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="3500000" y="3800000"/><a:ext cx="5000000" cy="1100000"/><a:chOff x="0" y="0"/><a:chExt cx="5000000" cy="1100000"/></a:xfrm></p:grpSpPr></p:grpSp>')
        grouped = text_shape(17, 'Grouped selected textbox', 'DUPLICATE', 0, 0, 5000000, 1100000)
        paragraph = grouped.find('.//{' + A + '}p')
        adjacent = copy.deepcopy(paragraph.find('{' + A + '}r')); adjacent.find('{' + A + '}t').text = ' adjacent preserved'; paragraph.append(adjacent)
        group.append(grouped); tree.append(group)
        field = text_shape(90, 'Automatic page field', '', 400000, 5200000, 4000000, 500000, 1400)
        field_paragraph = field.find('.//{' + A + '}p'); field_paragraph.remove(field_paragraph.find('{' + A + '}r'))
        field_paragraph.append(ET.fromstring(f'<a:fld xmlns:a="{A}" id="{{00000000-0000-0000-0000-000000000090}}" type="slidenum"><a:rPr lang="en-US" sz="1400"/><a:t>AUTOMATIC FIELD</a:t></a:fld>'))
        tree.append(field)
        if index == 2:
            root.set('xmlns:future', 'urn:hxai:b03:future')
            alternate = ET.Element('{' + MC + '}AlternateContent')
            choice = ET.SubElement(alternate, '{' + MC + '}Choice', {'Requires': 'future'})
            choice.append(text_shape(117, 'Unsupported compatibility choice', 'UNSUPPORTED CHOICE', 4572000, 2800000, 3500000, 650000))
            fallback = ET.SubElement(alternate, '{' + MC + '}Fallback')
            fallback.append(text_shape(118, 'Actual compatibility fallback', 'VISIBLE FALLBACK', 4572000, 2800000, 3500000, 650000))
            tree.append(alternate)
        package[part] = ET.tostring(root, encoding='utf-8', xml_declaration=True)
        name = Path(part).name
        package['ppt/slides/_rels/' + name + '.rels'] = serialized_rels
    master = ET.fromstring(package['ppt/slideMasters/slideMaster1.xml'])
    master.find('{' + P + '}cSld/{' + P + '}spTree').append(text_shape(100, 'B03 master watermark', 'MASTER B03 PRESERVED', 400000, 6300000, 8000000, 300000, 1200))
    package['ppt/slideMasters/slideMaster1.xml'] = ET.tostring(master, encoding='utf-8', xml_declaration=True)
    presentation = ET.fromstring(package['ppt/presentation.xml'])
    listing = presentation.find('{' + P + '}sldIdLst')
    for child in list(listing): listing.remove(child)
    relations = ET.fromstring(package['ppt/_rels/presentation.xml.rels'])
    for relation in list(relations):
        if relation.get('Type') == R + '/slide': relations.remove(relation)
    for index, part in enumerate(PARTS):
        identity = 'b03Slide' + str(index + 1)
        ET.SubElement(listing, '{' + P + '}sldId', {'id': str(256 + index), '{' + R + '}id': identity})
        ET.SubElement(relations, '{' + PKG + '}Relationship', {'Id': identity, 'Type': R + '/slide', 'Target': part.removeprefix('ppt/')})
    package['ppt/presentation.xml'] = ET.tostring(presentation, encoding='utf-8', xml_declaration=True)
    package['ppt/_rels/presentation.xml.rels'] = ET.tostring(relations, encoding='utf-8', xml_declaration=True)
    content = ET.fromstring(package['[Content_Types].xml'])
    known = {node.get('PartName') for node in content}
    for part in PARTS:
        if '/' + part not in known: ET.SubElement(content, '{' + CT + '}Override', {'PartName': '/' + part, 'ContentType': 'application/vnd.openxmlformats-officedocument.presentationml.slide+xml'})
    ET.register_namespace('', CT)  # Only our synthetic fixture; keep the established importer-compatible OPC representation.
    package['[Content_Types].xml'] = ET.tostring(content, encoding='utf-8', xml_declaration=True)
    source = folder / 'source.pptx'
    with ZipFile(source, 'x', ZIP_DEFLATED) as archive:
        for name, raw in package.items(): archive.writestr(name, raw)
    from pypdf import PdfWriter, PdfReader
    from pypdf.generic import DictionaryObject, NameObject, NumberObject, FloatObject, DecodedStreamObject, RectangleObject
    writer = PdfWriter()
    for index in range(3):
        page = writer.add_blank_page(width=400 if index == 0 else 300, height=600 if index == 0 else 500)
        if index < 2:
            font = DictionaryObject({NameObject('/Type'): NameObject('/Font'), NameObject('/Subtype'): NameObject('/Type1'), NameObject('/BaseFont'): NameObject('/Helvetica')})
            page[NameObject('/Resources')] = DictionaryObject({NameObject('/Font'): DictionaryObject({NameObject('/F1'): font})})
            stream = DecodedStreamObject(); stream.set_data(f'0 0 1 rg 50 100 120 80 re f BT /F1 20 Tf 30 400 Td (GEOMETRY PAGE {index + 1}) Tj ET'.encode())
            page[NameObject('/Contents')] = writer._add_object(stream)
        if index == 0:
            page.mediabox = RectangleObject([-20, -30, 380, 570]); page.cropbox = RectangleObject([10, 20, 330, 500]); page.rotate(90); page[NameObject('/UserUnit')] = FloatObject(1.25)
        elif index == 1:
            page.cropbox = RectangleObject([40, 50, 260, 450]); page.rotate(270); page[NameObject('/UserUnit')] = NumberObject(2)
        else:
            page.rotate(180)
    writer.add_page(PdfReader(scan_source).pages[0])
    with (folder / 'geometry.pdf').open('xb') as stream: writer.write(stream)
    (folder / 'FIXTURE.json').write_text(json.dumps({'baseSha256': digest(base), 'sourceSha256': digest(source), 'logicalParts': PARTS, 'hidden': HIDDEN, 'titles': TITLES, 'visibleLogicalSlides': [2, 3, 5], 'geometrySha256': digest(folder / 'geometry.pdf'), 'geometrySelection': [4, 1, 3, 2, 4], 'geometryScanSourceSha256': digest(scan_source)}, indent=2), encoding='utf-8')


def worker(folder):
    import uno
    from office_worker.engine import office_document, property_value
    from office_worker.package import inspect_package
    from office_worker.check_output import validate_pdf
    from office_worker.run import export, version
    result = {'rendererVersion': version(), 'documents': {}}
    for name in ('source', 'edited'):
        source = folder / (name + '.pptx'); source_hash = digest(source); inspect_package(source, 'pptx', source_hash)
        visible = folder / (name + '-visible.pdf')
        export(source, 'pptx', visible)
        assert validate_pdf(visible) == 3
        result['documents'][name + '-visible'] = {'sourceSha256': source_hash, 'pdfSha256': digest(visible), 'pages': 3, 'includeHiddenSlides': False, 'path': 'production_office_worker_export'}
        with office_document(source, 'pptx') as document:
            slides = document.getDrawPages(); assert slides.getCount() == 5
            actual_hidden = [not bool(slides.getByIndex(index).getPropertyValue('Visible')) for index in range(5)]
            assert actual_hidden == HIDDEN
            output = folder / (name + '-all.pdf')
            data = tuple(property_value(key, value) for key, value in {'ExportHiddenSlides': True, 'ExportNotes': False, 'ExportNotesPages': False, 'UseLosslessCompression': True, 'ReduceImageResolution': False}.items())
            document.storeToURL(output.resolve().as_uri(), (property_value('FilterName', 'impress_pdf_Export'), property_value('FilterData', uno.Any('[]com.sun.star.beans.PropertyValue', data)), property_value('Overwrite', False)))
            assert validate_pdf(output) == 5
            result['documents'][name + '-all'] = {'sourceSha256': source_hash, 'pdfSha256': digest(output), 'pages': 5, 'includeHiddenSlides': True, 'path': 'verification_only_hidden_slides_filter'}
        assert digest(source) == source_hash
    (folder / 'OFFICE_RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8')



def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--source-dir', type=Path)
    parser.add_argument('--scan-source', type=Path)
    parser.add_argument('--make-fixtures', action='store_true')
    parser.add_argument('--worker-child', action='store_true')
    args = parser.parse_args(); folder = args.run_dir.resolve()
    if args.worker_child: worker(folder); return
    folder.mkdir(parents=True, exist_ok=False)
    if args.make_fixtures:
        if args.scan_source is None: parser.error('--make-fixtures requires the actual synthetic native scan PDF')
        make_fixtures(folder, args.scan_source); return
    if os.name != 'nt' or args.source_dir is None: parser.error('Needs Windows and actual native PPT evidence')
    receipt = json.loads((args.source_dir / 'result.json').read_text(encoding='utf-8')); assert receipt['status'] == 'PASS'
    for name, key in [('source', 'sourceSha256'), ('edited', 'artifactSha256')]:
        path = args.source_dir / (name + '.pptx'); assert digest(path) == receipt[key]; shutil.copy2(path, folder / path.name)
    import pypdf
    from pypdf import PdfReader
    office = Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'LibreOffice/program'
    env = dict(os.environ); env['PATH'] = str(office) + os.pathsep + env.get('PATH', '')
    env['PYTHONPATH'] = os.pathsep.join([str(SERVICE), str(Path(pypdf.__file__).parents[1])])
    command = [str(office / 'python.exe'), str(Path(__file__).resolve()), '--worker-child', '--run-dir', str(folder)]
    started = time.monotonic(); child = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, timeout=240)
    (folder / 'real-office.log').write_bytes(child.stdout + b'\n' + child.stderr)
    (folder / 'WORKER_RUN.json').write_text(json.dumps({'command': command, 'cwd': str(ROOT), 'exitCode': child.returncode, 'seconds': round(time.monotonic() - started, 3)}, indent=2), encoding='utf-8'); child.check_returncode()
    labels = ['source-visible', 'edited-visible', 'source-all', 'edited-all']
    texts = {label: [page.extract_text() for page in PdfReader(folder / (label + '.pdf')).pages] for label in labels}
    for label, rows in texts.items():
        logical = list(range(5)) if label.endswith('-all') else [1, 2, 4]
        for text, index in zip(rows, logical, strict=True):
            assert TITLES[index] in text and 'MASTER B03 PRESERVED' in text
            assert 'DUPLICATE' in text and 'adjacent preserved' in text
        assert 'VISIBLE FALLBACK' in rows[2 if label.endswith('-all') else 1]
        assert all('UNSUPPORTED CHOICE' not in text for text in rows)
    assert 'Edited grouped text' in texts['edited-visible'][0]
    assert 'Edited hidden text' in texts['edited-all'][3] and all('Edited hidden text' not in text for text in texts['edited-visible'])
    with os.add_dll_directory(str(office)):
        library = ctypes.CDLL(str(office / 'pdfiumlo.dll')); configure_pdfium(library); library.FPDF_InitLibrary()
        try: counts = {label: render_pdf(library, folder / (label + '.pdf'), folder, label) for label in labels}
        finally: library.FPDF_DestroyLibrary()
    unchanged = {}
    for suffix, numbers in [('visible', [2, 3]), ('all', [1, 3, 5])]:
        for page in numbers:
            same = digest(folder / f'source-{suffix}-page-{page}.png') == digest(folder / f'edited-{suffix}-page-{page}.png')
            assert same, 'An unedited slide visual changed'
            unchanged[f'{suffix}-{page}'] = same
    result = {'status': 'PASS', 'scope': 'real_native_ppt_edit_all_logical_and_visible_slide_rendering', 'visibleLogicalToPdf': {'1': None, '2': 1, '3': 2, '4': None, '5': 3},
              'allLogicalToPdf': {str(index): index for index in range(1, 6)}, 'independentPdfiumPages': counts, 'unchangedSlidePixels': unchanged, 'textsPerPage': texts,
              'office': json.loads((folder / 'OFFICE_RESULT.json').read_text(encoding='utf-8')), 'layout': 'SCOPED_SYNTHETIC_SLIDES_ONLY', 'crossSoftware': 'NOT_RUN', 'containerIsolation': 'NOT_RUN',
              'scriptSha256': digest(Path(__file__)), 'artifacts': {p.name: digest(p) for p in folder.iterdir() if p.is_file()}}
    (folder / 'RESULT.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'status': 'PASS', 'pages': counts, 'visibleLogicalToPdf': result['visibleLogicalToPdf'], 'unchangedSlidePixels': unchanged}, indent=2))


if __name__ == '__main__':
    main()
