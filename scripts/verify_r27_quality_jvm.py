"""Offline real Kotlin math/Office checks. No Android/engine/model qualification."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import zipfile

try:
    from project_storage import enforce
except ModuleNotFoundError as error:
    if error.name != "project_storage":
        raise
    from scripts.project_storage import enforce

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--runtime-root', type=Path, required=True)
    p.add_argument('--run-dir', type=Path, required=True)
    p.add_argument('--baseline', action='store_true')
    a = p.parse_args()
    enforce(ROOT)
    out = a.run_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    lib = a.runtime_root.resolve() / '.local/gradle-9.6.0/lib'
    dependencies = [lib / n for n in ['kotlin-stdlib-2.3.21.jar', 'kotlinx-coroutines-core-jvm-1.10.2.jar',
                    'kotlinx-serialization-core-jvm-1.11.0.jar', 'kotlinx-serialization-json-jvm-1.11.0.jar', 'annotations-24.0.1.jar']]
    cache = Path(os.environ['USERPROFILE']) / '.gradle/caches/modules-2/files-2.1'
    dependencies += [next(cache.glob('junit/junit/4.13.2/*/*.jar')), next(cache.glob('org.hamcrest/hamcrest-core/1.3/*/*.jar'))]
    for path in (a.runtime_root / 'client/document/build').rglob('*.jar'):
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as z:
                if 'com/artifex/mupdf/fitz/PDFDocument.class' in z.namelist():
                    dependencies.append(path)
                    break
    if len(dependencies) != 8 or not all(path.is_file() for path in dependencies):
        raise ValueError('preinstalled_dependencies_missing')
    base = ROOT / 'client/app/src/main/java/me/rerere/rikkahub/feature'
    sources = sorted(path for path in (base / 'math').glob('*.kt') if path.name != 'MathTools.kt')
    materials = ['MaterialEditor', 'MaterialEquationXml', 'MaterialWordContent', 'OfficePackage',
                 'PresentationSlideOrder', 'SafeOfficeXml', 'SpreadsheetEditRegions', 'MathTranscription']
    sources += [base / 'material' / (name + '.kt') for name in materials]
    testbase = ROOT / 'client/app/src/test/java/me/rerere/rikkahub/feature'
    tests = [testbase / rel for rel in ['math/ProofBranchRulesTest.kt', 'math/ProofDerivationTest.kt', 'math/MathToolEnvelopeTest.kt',
             'material/SpreadsheetEditRegionsTest.kt', 'material/MathTranscriptionTest.kt']]
    if a.baseline:
        old = out / 'baseline'
        old.mkdir()
        for name in ['math/ProofDerivation.kt', 'math/MathVerification.kt', 'material/MaterialEditor.kt']:
            source = base / name
            content = subprocess.run(['git', 'show', '070f3d83c294ba0d4a2197d591055e75cff59ac3:' + source.relative_to(ROOT).as_posix()],
                                     cwd=ROOT, capture_output=True, check=True).stdout
            replacement = old / source.name
            replacement.write_bytes(content)
            sources[sources.index(source)] = replacement
    cp = os.pathsep.join(map(str, dependencies))
    jar = out / 'classes.jar'
    commands = []
    def run(label, command):
        start = time.monotonic()
        r = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=240)
        path = out / (label + '.log')
        path.write_bytes(r.stdout + b'\nSTDERR\n' + r.stderr)
        commands.append(dict(label=label, command=command, cwd=str(ROOT), exitCode=r.returncode,
                             seconds=round(time.monotonic()-start, 3), log=path.name, logSha256=digest(path)))
        (out / 'COMMANDS.json').write_text(json.dumps(commands, indent=2))
        return r
    java = shutil.which('java')
    compile_run = run('compile', [java, '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', str(jar), *map(str, sources+tests)])
    if compile_run.returncode:
        print(json.dumps({'status':'FAIL', 'stage':'compile', 'log':'compile.log'}))
        return 1
    names = ['me.rerere.rikkahub.feature.' + path.parent.name + '.' + path.stem for path in tests]
    executed = run('junit', [java, '-Dfile.encoding=UTF-8', '-cp', str(jar)+os.pathsep+cp, 'org.junit.runner.JUnitCore', *names])
    result = {'status':'PASS' if executed.returncode == 0 else 'FAIL', 'baseline':a.baseline,
              'scope':'REAL_KOTLIN_JVM_NOT_ANDROID_OR_MODEL_QUALITY',
              'sources':{str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path):digest(path) for path in sources+tests},
              'dependencies':{path.name:digest(path) for path in dependencies}, 'jarSha256':digest(jar)}
    (out / 'RESULT.json').write_text(json.dumps(result, indent=2))
    print(json.dumps({k:result[k] for k in ['status','baseline','scope']}))
    return executed.returncode


if __name__ == '__main__':
    raise SystemExit(main())
