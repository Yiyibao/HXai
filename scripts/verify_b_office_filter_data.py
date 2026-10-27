"""Prove requested lossless Office PDF export by decoding actual output pixels, not mocks."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
from zipfile import ZipFile, ZIP_DEFLATED
from verify_b_docx_omml import digest

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--source-docx', type=Path); parser.add_argument('--run-dir', type=Path, required=True); parser.add_argument('--worker-child', action='store_true')
    parser.add_argument('--replay-probe', type=Path, help='Use the identical retained negative-control DOCX bytes')
    args = parser.parse_args(); folder = args.run_dir.resolve()
    if args.worker_child:
        from office_worker.run import export
        export(folder / 'filter-probe.docx', 'docx', folder / 'output.pdf')
        return
    from PIL import Image
    from pypdf import PdfReader
    import pypdf
    assert args.source_docx is not None
    folder.mkdir(parents=True, exist_ok=False)
    image = Image.new('RGB', (128, 64)); image.putdata([(x * 2, y * 4, (x * 13 + y * 17) % 256) for y in range(64) for x in range(128)])
    raw = io.BytesIO(); image.save(raw, format='PNG'); expected = hashlib.sha256(image.tobytes()).hexdigest()
    if args.replay_probe is not None:
        shutil.copy2(args.replay_probe, folder / 'filter-probe.docx')
        with ZipFile(folder / 'filter-probe.docx') as original:
            assert original.read('word/media/image1.png') == raw.getvalue()
    else:
        with ZipFile(args.source_docx) as original, ZipFile(folder / 'filter-probe.docx', 'x', ZIP_DEFLATED) as output:
            assert 'word/media/image1.png' in original.namelist()
            for name in original.namelist(): output.writestr(name, raw.getvalue() if name == 'word/media/image1.png' else original.read(name))
    (folder / 'expected-image.png').write_bytes(raw.getvalue())
    binding = {'scope': 'SYNTHETIC_DERIVATIVE_FILTER_OPTION_PROBE_NOT_A_VERIFIED_MATERIAL_ARTIFACT', 'baseWordSha256': digest(args.source_docx), 'probeWordSha256': digest(folder / 'filter-probe.docx'), 'expectedRgbSha256': expected,
               'producerSourceSha256': digest(ROOT / 'server/document-service/office_worker/run.py'), 'scriptSha256': digest(Path(__file__))}
    (folder / 'INPUT.json').write_text(json.dumps(binding, indent=2), encoding='utf-8')
    office = Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'LibreOffice/program'
    env = dict(os.environ); env['PATH'] = str(office) + os.pathsep + env.get('PATH', ''); env['PYTHONPATH'] = os.pathsep.join([str(ROOT / 'server/document-service'), str(Path(pypdf.__file__).parents[1])])
    command = [str(office / 'python.exe'), str(Path(__file__).resolve()), '--worker-child', '--run-dir', str(folder)]
    started = time.monotonic(); result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, timeout=180)
    (folder / 'real-office.log').write_bytes(result.stdout + b'\n' + result.stderr)
    run = {'command': command, 'cwd': str(ROOT), 'exitCode': result.returncode, 'seconds': round(time.monotonic() - started, 3)}
    (folder / 'WORKER_RUN.json').write_text(json.dumps(run, indent=2), encoding='utf-8')
    decoded = []
    if (folder / 'output.pdf').is_file():
        reader = PdfReader(folder / 'output.pdf')
        objects = reader.pages[1]['/Resources']['/XObject']
        names = [operands[0] for operands, operator in reader.pages[1].get_contents().operations if operator == b'Do' and objects[operands[0]]['/Subtype'] == '/Image']
        for name in names:
            value = objects[name]
            if (value['/Width'], value['/Height']) != (128, 64): continue
            entry = next(image_file for image_file in reader.pages[1].images if image_file.indirect_reference == value.indirect_reference)
            actual = entry.image.convert('RGB')
            decoded.append({'filter': str(value.get('/Filter')), 'width': actual.width, 'height': actual.height, 'rgbSha256': hashlib.sha256(actual.tobytes()).hexdigest()})
    passed = result.returncode == 0 and len(decoded) == 1 and decoded[0]['rgbSha256'] == expected
    proof = {'status': 'PASS' if passed else 'FAIL', 'input': binding, 'worker': run, 'actualDrawnImage': decoded,
             'outputPdfSha256': digest(folder / 'output.pdf') if (folder / 'output.pdf').is_file() else None, 'losslessPixelsPreserved': len(decoded) == 1 and decoded[0]['rgbSha256'] == expected}
    (folder / 'RESULT.json').write_text(json.dumps(proof, indent=2), encoding='utf-8'); print(json.dumps(proof, indent=2))
    assert passed, 'Requested lossless export must preserve actual source RGB pixels and complete its owned worker successfully'


if __name__ == '__main__': main()
