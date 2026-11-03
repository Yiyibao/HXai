"""Prepare unchanged synthetic copies and read back explicitly supplied Office outputs.

Never attaches to WPS/Office, installs software or launches a user session. Engine
execution is a separate prerequisite; a declared engine is not an attestation.
"""
import argparse
import hashlib
import json
import math
from decimal import Decimal, InvalidOperation
from pathlib import Path
import re
import shutil
import sys

from office_roundtrip_contract import package, word_roundtrip, xlsx_roundtrip, presentation, workbook, require
from score_b_math_evaluation import load, write_new


def sha(path):
    with Path(path).open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def local(root, relative):
    require(isinstance(relative, str), 'invalid_bundle_path')
    path = Path(relative)
    require(not path.is_absolute() and '..' not in path.parts and ':' not in relative and '\\' not in relative, 'invalid_bundle_path')
    result = Path(root) / path
    require(result.resolve().is_relative_to(Path(root).resolve()) and not result.is_symlink(), 'invalid_bundle_path')
    return result


def pdf_signature(path):
    from pypdf import PdfReader
    require(path.is_file() and not path.is_symlink() and 1 <= path.stat().st_size <= 30 * 1024 * 1024, 'invalid_pdf')
    reader = PdfReader(path, strict=True)
    require(not reader.is_encrypted and 1 <= len(reader.pages) <= 200, 'invalid_pdf')
    result = []
    for page in reader.pages:
        text = ''.join(page.extract_text().split())
        # A broken ToUnicode map must not become a valid-looking replacement
        # string, or break UTF-8 evidence serialization after a claimed PASS.
        try:
            text.encode('utf-8', errors='strict')
        except UnicodeEncodeError:
            raise ValueError('pdf_text_invalid_unicode') from None
        result.append({'width': round(float(page.mediabox.width), 1),
                       'height': round(float(page.mediabox.height), 1), 'text': text})
    return result


def workbook_signature(parts):
    return json.loads(json.dumps(workbook(parts), sort_keys=True, default=str))


def recalculation_matches(actual, expected):
    """Formula caches may differ by one binary64 ULP across Office engines.

    All formulas, identities, types, non-formula data, dates and blank/unknown
    caches stay exact. This is numerical cache agreement, never an exact proof.
    """
    if len(actual) != len(expected):
        return False
    for left, right in zip(actual, expected, strict=True):
        if {k: v for k, v in left.items() if k != 'cells'} != {k: v for k, v in right.items() if k != 'cells'}:
            return False
        if left['cells'].keys() != right['cells'].keys():
            return False
        for address, cell in left['cells'].items():
            reference = right['cells'][address]
            if cell == reference:
                continue
            if {k: v for k, v in cell.items() if k != 'value'} != {k: v for k, v in reference.items() if k != 'value'}:
                return False
            if cell['type'] != 'n' or not cell['formula'] or cell['date'] or cell['value'] is None or reference['value'] is None:
                return False
            try:
                a, b = Decimal(cell['value']), Decimal(reference['value'])
                af, bf = float(a), float(b)
                if not a.is_finite() or not b.is_finite() or not math.isfinite(af) or not math.isfinite(bf):
                    return False
                tolerance = Decimal.from_float(max(math.ulp(af), math.ulp(bf)))
                if abs(a - b) > tolerance:
                    return False
            except (ValueError, TypeError, InvalidOperation, OverflowError):
                return False
    return True


def prepare(manifest_path, reference_dir, output):
    manifest, raw = load(manifest_path)
    require(manifest.get('contractVersion') == 1 and manifest.get('provenance') == 'PUBLIC_SYNTHETIC_NATIVE_FIXTURES', 'synthetic_manifest_required')
    jobs = manifest['jobs'];require(isinstance(jobs, list) and 1 <= len(jobs) <= 20, 'invalid_jobs')
    labels = set(); frozen = []
    # Validate all source/reference inputs before creating an output bundle.
    for job in jobs:
        label, kind = job['label'], job['kind']
        require(isinstance(label, str) and re.fullmatch('[a-zA-Z0-9_-]{1,80}', label) and label not in labels
                and kind in ('docx', 'xlsx', 'pptx'), 'invalid_job_identity')
        labels.add(label)
        source = Path(job['source'])
        require(sha(source) == job['sourceSha256'], 'source_digest_mismatch')
        package(source, kind)
        reference = Path(reference_dir) / f'{label}.{kind}'
        parts = package(reference, kind)
        expected_pdf = pdf_signature(Path(reference_dir) / f'{label}.pdf')
        frozen.append({**job, 'source': source, 'referenceSha256': sha(reference), 'pdfSignature': expected_pdf,
                       'workbookSignature': workbook_signature(parts) if kind == 'xlsx' else None})
    output = Path(output);output.mkdir(parents=True, exist_ok=False);(output / 'inputs').mkdir()
    binding = []
    for job in frozen:
        relative = f'inputs/{job["label"]}.{job["kind"]}'
        shutil.copyfile(job['source'], output / relative)
        require(sha(output / relative) == job['sourceSha256'], 'source_changed_during_copy')
        binding.append({**job, 'source': relative})
    seal = {'contractVersion': 1, 'kind': 'hxai_office_compatibility_bundle', 'jobs': binding,
            'sourceManifestSha256': hashlib.sha256(raw).hexdigest(), 'referenceExecution': 'HISTORICAL_REFERENCE_BYTES_ONLY',
            'referenceDirectory': str(Path(reference_dir).resolve()), 'engineExecution': 'NOT_RUN',
            'scope': 'FROZEN_SYNTHETIC_ROUNDTRIP_STRUCTURE_AND_PDF_TEXT; visual and engine execution separate'}
    write_new(output / 'BUNDLE.json', seal)
    write_new(output / 'ENGINE_RECORD_TEMPLATE.json', {'contractVersion': 1, 'engine': 'WPS', 'engineVersion': None,
        'origin': 'EXTERNAL_UNATTESTED', 'bundleSha256': sha(output / 'BUNDLE.json'),
        'jobs': [{'label': j['label'], 'saved': f'{j["label"]}.{j["kind"]}', 'savedSha256': None,
                  'pdf': f'{j["label"]}.pdf', 'pdfSha256': None, 'operations': []} for j in binding]})
    return {'status': 'PASS', 'scope': 'PREPARATION_ONLY', 'jobs': len(binding), 'bundleSha256': sha(output / 'BUNDLE.json'), 'engineExecution': 'NOT_RUN'}


def verify(bundle, outputs, record_path, report):
    seal, raw_seal = load(Path(bundle) / 'BUNDLE.json')
    require(type(seal.get('contractVersion')) is int and seal['contractVersion'] == 1
            and seal.get('kind') == 'hxai_office_compatibility_bundle', 'unsupported_bundle')
    record, record_raw = load(record_path)
    require(type(record.get('contractVersion')) is int and record['contractVersion'] == 1 and record.get('origin') == 'EXTERNAL_UNATTESTED'
            and record.get('bundleSha256') == hashlib.sha256(raw_seal).hexdigest()
            and record.get('engine') in ('WPS', 'Microsoft Office', 'LibreOffice')
            and isinstance(record.get('engineVersion'), str) and record['engineVersion'].strip(), 'invalid_engine_record')
    jobs = seal['jobs'];supplied = record['jobs']
    require(isinstance(jobs, list) and 1 <= len(jobs) <= 20 and isinstance(supplied, list)
            and len(supplied) == len(jobs), 'incomplete_output_coverage')
    require(len({j['label'] for j in supplied}) == len(supplied) and
            {j['label'] for j in supplied} == {j['label'] for j in jobs}, 'output_identity_mismatch')
    results = []
    for job in jobs:
        row = next(r for r in supplied if r['label'] == job['label'])
        required = {'OPEN', 'SAVE_AS', 'REOPEN', 'EXPORT_PDF'} | ({'FULL_RECALCULATE'} if job['kind'] == 'xlsx' else set())
        require(isinstance(row.get('operations'), list) and required <= set(row['operations']), 'missing_declared_engine_operation')
        source = local(bundle, job['source']);saved = local(outputs, row['saved']);pdf = local(outputs, row['pdf'])
        require(sha(source) == job['sourceSha256'] and sha(saved) == row['savedSha256'] and sha(pdf) == row['pdfSha256'], 'artifact_digest_mismatch')
        before, after = package(source, job['kind']), package(saved, job['kind'])
        if job['kind'] == 'docx':
            check = word_roundtrip(before, after)
        elif job['kind'] == 'xlsx':
            check = xlsx_roundtrip(before, after)
            require(recalculation_matches(workbook_signature(after), job['workbookSignature']), 'recalculated_values_or_types_mismatch')
        else:
            require(presentation(before) == presentation(after), 'presentation_logical_content_changed')
            check = {'logicalSlidesAndHiddenFlagsPreserved': True, 'logicalSlides': len(presentation(after))}
        # All logical slide pages are required, including the originally hidden slides.
        # A three-page export cannot pass against the sealed five-page reference.
        require(pdf_signature(pdf) == job['pdfSignature'], 'pdf_physical_pages_or_content_mismatch')
        results.append({'label': job['label'], 'sourceSha256': job['sourceSha256'], 'savedSha256': sha(saved),
                        'pdfSha256': sha(pdf), 'independentReadback': check, 'pdfPhysicalPages': len(job['pdfSignature'])})
    write_new(report, {'contractVersion': 1, 'status': 'PASS', 'scope': 'OFFLINE_FROZEN_STRUCTURE_AND_PDF_TEXT_READBACK_ONLY',
        'readbackRevision': 2, 'formulaCacheTolerance': 'ONE_BINARY64_ULP; exact non-formula data/types/dates/unknown preserved; not proof',
        'declaredEngine': record['engine'], 'declaredEngineVersion': record['engineVersion'],
        'engineExecution': 'EXTERNAL_UNATTESTED', 'visualLayout': 'NOT_EVALUATED', 'compatibilityQualification': 'NOT_EVALUATED',
        'bundleSha256': hashlib.sha256(raw_seal).hexdigest(), 'recordSha256': hashlib.sha256(record_raw).hexdigest(), 'jobs': results})
    return {'status': 'PASS', 'scope': 'OFFLINE_READBACK_ONLY', 'jobs': len(results), 'engineExecution': 'EXTERNAL_UNATTESTED'}


def main():
    parser = argparse.ArgumentParser(description=__doc__);subs = parser.add_subparsers(dest='command', required=True)
    prep = subs.add_parser('prepare');prep.add_argument('--manifest', type=Path, required=True)
    prep.add_argument('--reference-dir', type=Path, required=True);prep.add_argument('--out', type=Path, required=True)
    check = subs.add_parser('verify');check.add_argument('--bundle', type=Path, required=True)
    check.add_argument('--outputs', type=Path, required=True);check.add_argument('--engine-record', type=Path, required=True)
    check.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = prepare(args.manifest, args.reference_dir, args.out) if args.command == 'prepare' else verify(args.bundle, args.outputs, args.engine_record, args.report)
        print(json.dumps(result))
    except (ValueError, KeyError, TypeError, OSError, IndexError, RecursionError):
        print(json.dumps({'status': 'FAIL', 'errorCode': 'office_compatibility_readback_failed'}), file=sys.stderr);return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
