"""Compile frozen/current real editors and render the old invisible compatibility-branch edit."""
import argparse
import ctypes
import json
import os
from pathlib import Path
import shutil
import subprocess
from zipfile import ZipFile
from verify_b_docx_omml import digest, configure_pdfium, render_pdf

ROOT = Path(__file__).resolve().parents[1]
REL = Path('client/app/src/main/java/me/rerere/rikkahub/feature/material')
NAMES = ['MaterialEditor', 'MaterialWordContent', 'MaterialEquationXml', 'SafeOfficeXml', 'OfficePackage', 'PresentationSlideOrder']
CONSUMER = '''import java.io.File
import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.material.MaterialEditor
fun main(args: Array<String>) {
    val source = File(args[0]); val output = File(args[1]); val before = source.readBytes()
    val run = MaterialEditor.textRuns(source, "pptx", 3, 0, 50).getValue("runs").jsonArray.single {
        it.jsonObject.getValue("text").jsonPrimitive.content == "UNSUPPORTED CHOICE"
    }.jsonObject
    val spec = buildJsonObject { put("slide", 3); put("index", run.getValue("index")); put("expectedText", "UNSUPPORTED CHOICE"); put("text", "FALSELY EDITED BRANCH") }
    val failure = runCatching { MaterialEditor.replaceTextRun(source, output, "pptx", spec) }.exceptionOrNull()
    check(source.readBytes().contentEquals(before))
    if (args[2] == "baseline") {
        check(run.getValue("editable").jsonPrimitive.boolean && failure == null && output.isFile)
        check(MaterialEditor.textRuns(output, "pptx", 3, 0, 50).getValue("runs").jsonArray.any { it.jsonObject.getValue("text").jsonPrimitive.content == "FALSELY EDITED BRANCH" })
        println("BASELINE_WROTE_UNRENDERED_BRANCH")
    } else {
        check(!run.getValue("editable").jsonPrimitive.boolean && failure is IllegalArgumentException && !output.exists())
        println("CURRENT_REJECTED_WITHOUT_OUTPUT")
    }
}
'''


def command(folder, name, args, env=None):
    result = subprocess.run([str(value) for value in args], cwd=ROOT, env=env, capture_output=True, timeout=240)
    (folder / (name + '.log')).write_bytes(result.stdout + b'\n' + result.stderr)
    (folder / (name + '.json')).write_text(json.dumps({'cwd': str(ROOT), 'command': [str(value) for value in args], 'exitCode': result.returncode}, indent=2), encoding='utf-8')
    result.check_returncode()


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--baseline', type=Path); parser.add_argument('--source-pptx', type=Path); parser.add_argument('--run-dir', type=Path, required=True); parser.add_argument('--worker-child', action='store_true')
    args = parser.parse_args(); folder = args.run_dir.resolve()
    if args.worker_child:
        from office_worker.run import export
        from office_worker.check_output import validate_pdf
        for name in ('source', 'invisible-edit'):
            export(folder / (name + '.pptx'), 'pptx', folder / (name + '.pdf'))
            assert validate_pdf(folder / (name + '.pdf')) == 3
        return
    assert args.baseline is not None and args.source_pptx is not None
    folder.mkdir(parents=True, exist_ok=False); shutil.copy2(args.source_pptx, folder / 'source.pptx')
    (folder / 'CompatBoundary.kt').write_text(CONSUMER, encoding='utf-8')
    library = ROOT / '.local/gradle-9.6.0/lib'
    jars = [library / name for name in ['kotlin-stdlib-2.3.21.jar', 'kotlinx-coroutines-core-jvm-1.10.2.jar', 'kotlinx-serialization-core-jvm-1.11.0.jar', 'kotlinx-serialization-json-jvm-1.11.0.jar', 'annotations-24.0.1.jar']]
    pdf_jar = None
    for candidate in (ROOT / 'client/document/build').rglob('*.jar'):
        with ZipFile(candidate) as archive:
            if 'com/artifex/mupdf/fitz/PDFDocument.class' in archive.namelist(): pdf_jar = candidate; break
    assert pdf_jar is not None; jars.append(pdf_jar)
    cp = os.pathsep.join(str(path) for path in jars); java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    source_inputs = {}
    for label, root in [('baseline', args.baseline), ('current', ROOT)]:
        sources = [root / REL / (name + '.kt') for name in NAMES]
        source_inputs[label] = {str(path): digest(path) for path in sources}
        classes = folder / (label + '-classes')
        command(folder, label + '-compile', [java, '-cp', library / '*', 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', classes, *sources, folder / 'CompatBoundary.kt'])
        target = folder / ('invisible-edit.pptx' if label == 'baseline' else 'must-not-exist.pptx')
        command(folder, label + '-consume', [java, '-cp', str(classes) + os.pathsep + cp, 'CompatBoundaryKt', folder / 'source.pptx', target, label])
    assert digest(folder / 'source.pptx') == digest(args.source_pptx)
    import pypdf
    from pypdf import PdfReader
    office = Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'LibreOffice/program'
    env = dict(os.environ); env['PATH'] = str(office) + os.pathsep + env.get('PATH', ''); env['PYTHONPATH'] = os.pathsep.join([str(ROOT / 'server/document-service'), str(Path(pypdf.__file__).parents[1])])
    command(folder, 'actual-office-render', [office / 'python.exe', Path(__file__).resolve(), '--worker-child', '--run-dir', folder], env)
    texts = {name: [page.extract_text() for page in PdfReader(folder / (name + '.pdf')).pages] for name in ('source', 'invisible-edit')}
    assert texts['source'] == texts['invisible-edit']
    assert 'VISIBLE FALLBACK' in texts['invisible-edit'][1] and all('FALSELY EDITED BRANCH' not in text for text in texts['invisible-edit'])
    with os.add_dll_directory(str(office)):
        library_dll = ctypes.CDLL(str(office / 'pdfiumlo.dll')); configure_pdfium(library_dll); library_dll.FPDF_InitLibrary()
        try: pages = {name: render_pdf(library_dll, folder / (name + '.pdf'), folder, name) for name in ('source', 'invisible-edit')}
        finally: library_dll.FPDF_DestroyLibrary()
    comparisons = []
    for page in range(1, 4):
        assert digest(folder / f'source-page-{page}.png') == digest(folder / f'invisible-edit-page-{page}.png')
        comparisons.append({'pdfPhysicalPage': page, 'wholePagePixelsUnchanged': True})
    result = {'status': 'PASS', 'baseline': 'REAL_OLD_EDITOR_WROTE_TEXT_BUT_ALL_VISIBLE_PAGES_UNCHANGED', 'current': 'REJECTED_WITHOUT_OUTPUT', 'sourceSha256': digest(args.source_pptx),
              'oldWrongOutputSha256': digest(folder / 'invisible-edit.pptx'), 'pages': pages, 'pageComparisons': comparisons, 'sourceInputs': source_inputs,
              'dependencies': {str(path): digest(path) for path in jars}, 'scriptSha256': digest(Path(__file__)), 'artifacts': {path.name: digest(path) for path in folder.iterdir() if path.is_file()}}
    (folder / 'RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8'); print(json.dumps({key: result[key] for key in ('status', 'baseline', 'current', 'pages')}, indent=2))


if __name__ == '__main__': main()
