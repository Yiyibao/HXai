"""Host publishes fixed validated assets; the PDF parser runs only in a sandbox."""
import hashlib
import json
import os
import shutil


def validate_office_output(directory, options):
    file = directory / 'output/manifest.json'
    if not file.is_file() or file.is_symlink() or file.stat().st_size > 65536:
        raise ValueError('invalid_conversion_manifest')
    manifest = json.loads(file.read_text())
    if (set(manifest) != {'contractVersion', 'operation', 'sourceSha256', 'sourceExtension', 'renderer', 'rendererVersion', 'renderOptions', 'pageCount', 'warnings', 'assets'}
            or manifest['contractVersion'] != 1 or manifest['operation'] != 'office_pdf'
            or manifest['sourceSha256'] != options['sourceSha256'] or manifest['sourceExtension'] != options['extension']
            or manifest['renderer'] != 'LibreOffice' or manifest['rendererVersion'] != options['rendererVersion']
            or manifest['renderOptions'] != options['renderOptions'] or type(manifest['pageCount']) is not int
            or not 1 <= manifest['pageCount'] <= 200
            or not isinstance(manifest['warnings'], list) or len(manifest['warnings']) > 20
            or any(not isinstance(w, str) or len(w) > 16000 for w in manifest['warnings'])):
        raise ValueError('invalid_conversion_manifest')
    source = directory / 'output/output.pdf'
    if source.is_symlink() or not source.is_file() or not 1 <= source.stat().st_size <= 30 * 1024 * 1024:
        raise ValueError('invalid_conversion_pdf')
    asset = {'assetId': 'pdf', 'path': 'output.pdf', 'mediaType': 'application/pdf', 'size': source.stat().st_size,
             'sha256': hashlib.sha256(source.read_bytes()).hexdigest()}
    if manifest['assets'] != [asset]:
        raise ValueError('conversion_asset_mismatch')
    staging = directory / 'publishing'; staging.mkdir()
    shutil.copyfile(source, staging / 'output.pdf')
    if hashlib.sha256((staging / 'output.pdf').read_bytes()).hexdigest() != asset['sha256']:
        raise ValueError('conversion_copy_mismatch')
    os.replace(staging, directory / 'published')
    return manifest
