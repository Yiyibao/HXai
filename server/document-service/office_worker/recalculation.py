"""Fixed XLSX/witness contract. Verification reads actual files without the source or UNO."""
import hashlib
import json
import math
import re

from office_worker.calculation import verify_saved
from office_worker.contracts import WITNESS_LIMIT, FORMULA_LIMIT, XLSX_MEDIA_TYPE, RECALCULATION_OPTIONS


def asset(path, asset_id, media_type, limit):
    if path.is_symlink() or not path.is_file() or not 1 <= path.stat().st_size <= limit:
        raise ValueError('invalid_recalculation_asset')
    return {'assetId': asset_id, 'path': path.name, 'mediaType': media_type,
            'size': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def read_witness(path, source_sha256, output_sha256):
    if path.is_symlink() or not path.is_file() or not 1 <= path.stat().st_size <= WITNESS_LIMIT:
        raise ValueError('invalid_calculation_witness')
    value = json.loads(path.read_text(encoding='utf-8'))
    if (set(value) != {'contractVersion', 'sourceSha256', 'outputSha256', 'sheetNames', 'cells'}
            or type(value['contractVersion']) is not int or value['contractVersion'] != 1
            or value['sourceSha256'] != source_sha256 or value['outputSha256'] != output_sha256):
        raise ValueError('invalid_calculation_witness')
    sheets = value['sheetNames']
    if (not isinstance(sheets, list) or not 1 <= len(sheets) <= 2000
            or any(not isinstance(name, str) or not 1 <= len(name) <= 31 for name in sheets)
            or len(sheets) != len(set(sheets))):
        raise ValueError('invalid_calculation_witness')
    cells = value['cells']
    if not isinstance(cells, list) or len(cells) > FORMULA_LIMIT:
        raise ValueError('invalid_calculation_witness')
    expected = {}
    for cell in cells:
        if (not isinstance(cell, dict) or set(cell) != {'sheet', 'cell', 'number', 'text'}
                or cell['sheet'] not in sheets or not isinstance(cell['cell'], str)
                or not re.fullmatch(r'[A-Z]{1,3}[1-9][0-9]{0,6}', cell['cell'])
                or not isinstance(cell['text'], str) or len(cell['text']) > 32767
                or cell['number'] is not None and (type(cell['number']) not in (int, float) or not math.isfinite(cell['number']))):
            raise ValueError('invalid_calculation_witness')
        address = cell['cell']
        column = 0
        for char in address.rstrip('0123456789'):
            column = column * 26 + ord(char) - 64
        row = int(address.lstrip('ABCDEFGHIJKLMNOPQRSTUVWXYZ'))
        if column > 16384 or row > 1048576:
            raise ValueError('invalid_calculation_witness')
        key = (cell['sheet'], column - 1, row - 1)
        if key in expected:
            raise ValueError('invalid_calculation_witness')
        expected[key] = (cell['number'], cell['text'])
    return value, expected


def verify_output(directory):
    manifest_path = directory / 'manifest.json'
    if manifest_path.is_symlink() or not manifest_path.is_file() or not 1 <= manifest_path.stat().st_size <= 65536:
        raise ValueError('invalid_recalculation_manifest')
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if (set(manifest) != {'contractVersion', 'operation', 'sourceSha256', 'sourceExtension', 'renderer', 'rendererVersion',
                          'renderOptions', 'recalculation', 'warnings', 'assets'}
            or type(manifest['contractVersion']) is not int or manifest['contractVersion'] != 1
            or manifest['operation'] != 'xlsx_recalculate' or manifest['sourceExtension'] != 'xlsx'
            or not isinstance(manifest['sourceSha256'], str) or not re.fullmatch(r'[a-f0-9]{64}', manifest['sourceSha256'])
            or manifest['renderer'] != 'LibreOffice' or not isinstance(manifest['rendererVersion'], str) or not manifest['rendererVersion']
            or manifest['renderOptions'] != RECALCULATION_OPTIONS
            or not isinstance(manifest['warnings'], list) or len(manifest['warnings']) > 20
            or any(not isinstance(w, str) or len(w) > 16000 for w in manifest['warnings'])):
        raise ValueError('invalid_recalculation_manifest')
    workbook = directory / 'recalculated.xlsx'
    witness = directory / 'calculation.json'
    xlsx_asset = asset(workbook, 'xlsx', XLSX_MEDIA_TYPE, 30 * 1024 * 1024)
    witness_asset = asset(witness, 'calculation', 'application/json', WITNESS_LIMIT)
    if manifest['assets'] != [xlsx_asset, witness_asset]:
        raise ValueError('recalculation_asset_mismatch')
    value, expected = read_witness(witness, manifest['sourceSha256'], xlsx_asset['sha256'])
    evidence = verify_saved(workbook, expected, value['sheetNames'])
    proof = {'status': 'VERIFIED', 'scope': evidence['scope'], 'formulaCount': evidence['formulaCount'],
             'sheetCount': evidence['sheetCount'], 'numericRelativeTolerance': 1e-12,
             'numericAbsoluteTolerance': 1e-15, 'layout': 'NOT_VERIFIED'}
    if manifest['recalculation'] != proof:
        raise ValueError('invalid_recalculation_manifest')
    return manifest


def produce(source, output, options, renderer_version):
    from office_worker.engine import recalculate_xlsx
    if (options['operation'] != 'xlsx_recalculate' or options['extension'] != 'xlsx'
            or options['renderOptions'] != RECALCULATION_OPTIONS or options['rendererVersion'] != renderer_version):
        raise ValueError('invalid_recalculation_options')
    result = recalculate_xlsx(source, output / 'recalculated.xlsx', options['sourceSha256'], output / 'calculation.json')
    manifest = {'contractVersion': 1, 'operation': 'xlsx_recalculate', 'sourceSha256': options['sourceSha256'],
                'sourceExtension': 'xlsx', 'renderer': 'LibreOffice', 'rendererVersion': renderer_version,
                'renderOptions': RECALCULATION_OPTIONS,
                'recalculation': {'status': 'VERIFIED', 'scope': result['scope'], 'formulaCount': result['formulaCount'],
                                  'sheetCount': result['sheetCount'], 'numericRelativeTolerance': 1e-12,
                                  'numericAbsoluteTolerance': 1e-15, 'layout': 'NOT_VERIFIED'},
                'warnings': ['LibreOffice workbook recalculation; verify complex layout in target Office.'],
                'assets': [asset(output / 'recalculated.xlsx', 'xlsx', XLSX_MEDIA_TYPE, 30 * 1024 * 1024),
                           asset(output / 'calculation.json', 'calculation', 'application/json', WITNESS_LIMIT)]}
    (output / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
    verify_output(output)
    return manifest
