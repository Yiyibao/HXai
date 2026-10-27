"""Offline targeted JVM verification using the installed Gradle Kotlin compiler.

No install, network, model calls or product mutation. Android parser/tool tests are
separate instrumentation tests; these JVM checks are not Android evidence.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'HXai_CHANGE_REVIEW/FILE-05-OOXML-PPT-20261012'
MAIN = ROOT / 'client/app/src/main/java/me/rerere/rikkahub/feature/material'
TEST = ROOT / 'client/app/src/test/java/me/rerere/rikkahub/feature/material'

def run(name, command, expected=0):
    start = time.time()
    result = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True, encoding='utf-8', errors='replace')
    log = OUT / 'logs' / (name + '.log')
    log.write_text('$ ' + subprocess.list2cmdline(list(map(str, command))) + '\nCWD: ' + str(ROOT) + '\n' + result.stdout + result.stderr + '\nexit_code=' + str(result.returncode), encoding='utf-8')
    record = dict(name=name, command=list(map(str, command)), cwd=str(ROOT), exit_code=result.returncode, expected_exit=expected,
                  status='PASS' if result.returncode == expected else 'FAIL', seconds=round(time.time()-start, 3), log=str(log.relative_to(ROOT)), log_sha256=hashlib.sha256(log.read_bytes()).hexdigest())
    report = OUT / 'JVM_RUNS.json'
    rows = json.loads(report.read_text()) if report.exists() else []
    report.write_text(json.dumps(rows + [record], indent=2), encoding='utf-8')
    print(name, result.returncode, result.stdout[-3000:], result.stderr[-3000:])
    return result.returncode == expected

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', action='store_true')
    parser.add_argument('--run-id')
    args = parser.parse_args()
    lib = ROOT / '.local/gradle-9.6.0/lib'
    cache = Path(os.environ['USERPROFILE']) / '.gradle/caches/modules-2/files-2.1'
    junit = next(cache.glob('junit/junit/4.13.2/*/*.jar'))
    hamcrest = next(cache.glob('org.hamcrest/hamcrest-core/1.3/*/*.jar'))
    document = next(p for p in (ROOT / 'client/document/build').rglob('*.jar')
                    if zipfile.is_zipfile(p) and 'com/artifex/mupdf/fitz/PDFDocument.class' in zipfile.ZipFile(p).namelist())
    jars = [lib / n for n in ['kotlin-stdlib-2.3.21.jar', 'kotlinx-coroutines-core-jvm-1.10.2.jar',
                              'kotlinx-serialization-core-jvm-1.11.0.jar', 'kotlinx-serialization-json-jvm-1.11.0.jar', 'annotations-24.0.1.jar']]
    cp = os.pathsep.join(map(str, jars + [junit, hamcrest, document]))
    name = args.run_id or ('baseline-jvm' if args.baseline else 'fixed-jvm-' + str(time.time_ns()))
    classes = ROOT / '.local' / ('file-05-' + name)
    classes.mkdir(exist_ok=True)
    sources = [OUT / 'baseline/MaterialEditor.kt'] if args.baseline else [MAIN / (n + '.kt') for n in
        ['MaterialEditor', 'MaterialReader', 'MaterialWordContent', 'SafeOfficeXml', 'PresentationSlideOrder', 'OfficePackage']]
    tests = [OUT / 'baseline/OfficeSafetyTest.kt'] if args.baseline else [TEST / (n + '.kt') for n in ['OfficeSafetyTest', 'MaterialEditorTest', 'OfficePackageTest', 'SafeOfficeXmlTest']]
    inputs = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources + tests + jars + [junit, hamcrest, document]}
    (OUT / (name + '-inputs.json')).write_text(json.dumps(inputs, indent=2))
    if not run(name + '-compile', ['java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                                    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', classes, *sources, *tests]):
        return 1
    names = ['me.rerere.rikkahub.feature.material.' + p.stem for p in tests]
    ok = run(name + '-test', ['java', '-cp', str(classes) + os.pathsep + cp, 'org.junit.runner.JUnitCore', *names], expected=1 if args.baseline else 0)
    return 0 if ok else 1

if __name__ == '__main__':
    raise SystemExit(main())
