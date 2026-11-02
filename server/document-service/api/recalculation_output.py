"""Publish only fixed XLSX/witness assets after bounded independent readback."""
import hashlib
import os
import shutil

from office_worker.recalculation import verify_output


def validate_recalculation_output(directory, options, *, publish=True):
    manifest = verify_output(directory / 'output')
    if (manifest['sourceSha256'] != options['sourceSha256'] or manifest['sourceExtension'] != options['extension']
            or manifest['rendererVersion'] != options['rendererVersion'] or manifest['renderOptions'] != options['renderOptions']
            or manifest['operation'] != options['operation']):
        raise ValueError('recalculation_binding_mismatch')
    if not publish:return manifest
    staging = directory / 'publishing'
    staging.mkdir()
    for asset in manifest['assets']:
        source = directory / 'output' / asset['path']
        copied = staging / asset['path']
        shutil.copyfile(source, copied)
        if copied.stat().st_size != asset['size'] or hashlib.sha256(copied.read_bytes()).hexdigest() != asset['sha256']:
            raise ValueError('recalculation_copy_mismatch')
    os.replace(staging, directory / 'published')
    return manifest
