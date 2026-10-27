"""Synthetic PPT shape-locator fixture and real local UNO/PDFium acceptance; no installation/upload."""
import argparse
import copy
import ctypes
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from verify_b_docx_omml import digest, configure_pdfium, render_pdf

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / 'server/document-service'
P = 'http://schemas.openxmlformats.org/presentationml/2006/main'
A = 'http://schemas.openxmlformats.org/drawingml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
PKG = 'http://schemas.openxmlformats.org/package/2006/relationships'


def fixture(folder):
    base = ROOT / 'qa/p0-completion-20261009/samples/rich-source.pptx'
    with zipfile.ZipFile(base) as archive:
        parts = {name: archive.read(name) for name in archive.namelist()}
    parts['ppt/slides/slide9.xml'] = parts.pop('ppt/slides/slide1.xml')
    parts['ppt/slides/_rels/slide9.xml.rels'] = parts.pop('ppt/slides/_rels/slide1.xml.rels')
    original_second = parts['ppt/slides/slide2.xml']
    parts['ppt/slides/slide1.xml'] = original_second
    parts['ppt/slides/_rels/slide1.xml.rels'] = parts['ppt/slides/_rels/slide2.xml.rels']
    hidden = ET.fromstring(original_second); hidden.set('show', '0')
    hidden.find('.//{' + A + '}t').text = 'HIDDEN LOGICAL FIRST'
    parts['ppt/slides/slide2.xml'] = ET.tostring(hidden, encoding='utf-8', xml_declaration=True)
    last = ET.fromstring(original_second); last.find('.//{' + A + '}t').text = 'VISIBLE LAST PRESERVED'
    parts['ppt/slides/slide1.xml'] = ET.tostring(last, encoding='utf-8', xml_declaration=True)
    selected = ET.fromstring(parts['ppt/slides/slide9.xml'])
    tree = selected.find('.//{' + P + '}spTree')
    shapes = tree.findall('{' + P + '}sp')
    for shape in shapes:
        shape.find('.//{' + A + '}t').text = 'DUPLICATE'
    grouped = copy.deepcopy(shapes[1])
    grouped.find('.//{' + P + '}cNvPr').set('id', '17')
    grouped.find('.//{' + P + '}cNvPr').set('name', 'Grouped selected textbox')
    paragraph = grouped.find('.//{' + A + '}p')
    second_run = copy.deepcopy(paragraph.find('{' + A + '}r'))
    second_run.find('{' + A + '}t').text = ' adjacent preserved'
    paragraph.append(second_run)
    group = ET.fromstring(f'<p:grpSp xmlns:p="{P}" xmlns:a="{A}"><p:nvGrpSpPr><p:cNvPr id="16" name="Preserved group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="1000000"/><a:ext cx="9144000" cy="5858000"/><a:chOff x="0" y="0"/><a:chExt cx="9144000" cy="6858000"/></a:xfrm></p:grpSpPr></p:grpSp>')
    group.append(grouped); tree.append(group)
    field = copy.deepcopy(shapes[1])
    field.find('.//{' + P + '}cNvPr').set('id', '90')
    field.find('.//{' + P + '}cNvPr').set('name', 'Date field must not be edited')
    field_p = field.find('.//{' + A + '}p')
    for child in list(field_p): field_p.remove(child)
    field_p.append(ET.fromstring(f'<a:fld xmlns:a="{A}" id="{{00000000-0000-0000-0000-000000000001}}" type="datetime"><a:rPr lang="en-US"/><a:t>DATE FIELD</a:t></a:fld>'))
    tree.append(field)
    parts['ppt/slides/slide9.xml'] = ET.tostring(selected, encoding='utf-8', xml_declaration=True)
    presentation = ET.fromstring(parts['ppt/presentation.xml'])
    listing = presentation.find('{' + P + '}sldIdLst')
    old = list(listing)
    for item in old: listing.remove(item)
    listing.append(old[1]); listing.append(old[0])
    ET.SubElement(listing, '{' + P + '}sldId', {'id': '258', '{' + R + '}id': 'rId99'})
    parts['ppt/presentation.xml'] = ET.tostring(presentation, encoding='utf-8', xml_declaration=True)
    relationships = ET.fromstring(parts['ppt/_rels/presentation.xml.rels'])
    next(item for item in relationships if item.get('Id') == 'rId7').set('Target', 'slides/slide9.xml')
    ET.SubElement(relationships, '{' + PKG + '}Relationship', {'Id': 'rId99', 'Type': R + '/slide', 'Target': 'slides/slide1.xml'})
    parts['ppt/_rels/presentation.xml.rels'] = ET.tostring(relationships, encoding='utf-8', xml_declaration=True)
    content = ET.fromstring(parts['[Content_Types].xml'])
    old_type = next(item for item in content if item.get('PartName') == '/ppt/slides/slide1.xml')
    extra = copy.deepcopy(old_type); extra.set('PartName', '/ppt/slides/slide9.xml'); content.append(extra)
    # Keep the template's default OPC namespace: LibreOffice's importer does not
    # accept prefixed Content_Types even with an explicit import filter.
    ET.register_namespace('', 'http://schemas.openxmlformats.org/package/2006/content-types')
    parts['[Content_Types].xml'] = ET.tostring(content, encoding='utf-8', xml_declaration=True)
    target = folder / 'source.pptx'
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in parts.items(): archive.writestr(name, data)
    (folder / 'FIXTURE.json').write_text(json.dumps({'baseSha256': digest(base), 'sourceSha256': digest(target), 'logicalParts': ['ppt/slides/slide2.xml', 'ppt/slides/slide9.xml', 'ppt/slides/slide1.xml'], 'hidden': [True, False, False], 'targetLogicalSlide': 2, 'targetShapeId': '17'}, indent=2), encoding='utf-8')


def worker(folder):
    from office_worker.package import inspect_package
    from office_worker.run import export, version
    from office_worker.check_output import validate_pdf
    result = {'rendererVersion': version(), 'documents': {}}
    for name in ['source', 'edited']:
        source = folder / (name + '.pptx'); source_hash = digest(source)
        inspect_package(source, 'pptx', source_hash)
        target = folder / (name + '.pdf'); export(source, 'pptx', target)
        assert digest(source) == source_hash
        result['documents'][name] = {'sourceSha256': source_hash, 'pdfSha256': digest(target), 'pages': validate_pdf(target)}
    (folder / 'OFFICE_RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-dir', required=True, type=Path)
    parser.add_argument('--source-dir', type=Path)
    parser.add_argument('--make-fixture', action='store_true')
    parser.add_argument('--worker-child', action='store_true')
    args = parser.parse_args(); folder = args.run_dir.resolve()
    if args.worker_child: worker(folder); return
    folder.mkdir(parents=True, exist_ok=False)
    if args.make_fixture: fixture(folder); return
    if args.source_dir is None or os.name != 'nt': parser.error('Need actual native source-dir and Windows LibreOffice')
    import pypdf
    from pypdf import PdfReader
    receipt = json.loads((args.source_dir / 'result.json').read_text(encoding='utf-8'))
    assert receipt['status'] == 'PASS'
    for name, key in [('source', 'sourceSha256'), ('edited', 'artifactSha256')]:
        file = args.source_dir / (name + '.pptx'); assert digest(file) == receipt[key]
        shutil.copy2(file, folder / file.name)
    office = Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'LibreOffice/program'
    env = dict(os.environ); env['PATH'] = str(office) + os.pathsep + env.get('PATH', '')
    env['PYTHONPATH'] = os.pathsep.join([str(SERVICE), str(Path(pypdf.__file__).parents[1])])
    command = [str(office / 'python.exe'), str(Path(__file__).resolve()), '--worker-child', '--run-dir', str(folder)]
    run = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, timeout=180)
    (folder / 'real-office.log').write_bytes(run.stdout + b'\n' + run.stderr)
    (folder / 'WORKER_RUN.json').write_text(json.dumps({'cwd': str(ROOT), 'command': command, 'exitCode': run.returncode}, indent=2), encoding='utf-8')
    run.check_returncode()
    texts = {name: [page.extract_text() for page in PdfReader(folder / (name + '.pdf')).pages] for name in ['source', 'edited']}
    for name, pages in texts.items():
        assert len(pages) == 2 and all('HIDDEN LOGICAL FIRST' not in text for text in pages)
        assert 'VISIBLE LAST PRESERVED' in pages[1] and 'DUPLICATE' in pages[0]
    assert 'Edited selected shape' in texts['edited'][0] and 'Edited selected shape' not in texts['source'][0]
    with os.add_dll_directory(str(office)):
        library = ctypes.CDLL(str(office / 'pdfiumlo.dll')); configure_pdfium(library); library.FPDF_InitLibrary()
        try:
            for name in ['source', 'edited']: assert render_pdf(library, folder / (name + '.pdf'), folder, name) == 2
        finally: library.FPDF_DestroyLibrary()
    report = {'status': 'PASS', 'logicalToPdf': {'1': None, '2': 1, '3': 2}, 'texts': texts, 'office': json.loads((folder / 'OFFICE_RESULT.json').read_text()), 'layout': 'NOT_VERIFIED', 'crossSoftware': 'NOT_RUN', 'containerIsolation': 'NOT_RUN', 'scriptSha256': digest(Path(__file__)), 'pdfiumSha256': digest(office / 'pdfiumlo.dll'), 'artifacts': {p.name: digest(p) for p in folder.iterdir() if p.is_file()}}
    (folder / 'RESULT.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__': main()
