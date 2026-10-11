"""Local contract probes; no network, real Store and output validator."""
import hashlib
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'server/document-service'))
from api.output import validate_output
from api.store import Store

with tempfile.TemporaryDirectory() as temp:
    root = Path(temp)
    store = Store(root)
    owner, _ = store.issue()
    source = root / 'uploads/source.pdf'
    source.write_bytes(b'%PDF-local-contract-probe')
    options = dict(sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                   extension='pdf', pages=[1, 1], tier='basic',
                   parserVersion='4.0.10', adapterVersion='1')
    row = store.enqueue(owner, 'lost-receipt-probe', 'probe-fingerprint', options, source)
    assert row['state'] == 'QUEUED'
    # The client may not know this remote ID when the acknowledgement is lost.
    print('Accepted upload without client receipt:', row['state'])
    # Store already supports durable cancellation by the known request key.
    cancelled = store.cancel_request(owner, 'lost-receipt-probe')
    assert cancelled['state'] == 'CANCELLED'
    print('Cancellation by request key:', cancelled['state'])

    directory = root / 'jobs' / row['id']
    document = dict(contractVersion=1, tier='basic', parserVersion='4.0.10',
                    adapterVersion='1', resultRevision=1, totalPages=1,
                    requestedRange=[1, 1], processedPages=[1], blankPages=[],
                    failedPages=[], complete=True, sourceSha256=options['sourceSha256'],
                    warnings=[], blocks=[dict(blockId='p1-b0', page=1,
                    type='table_body', text='x' * 24001)])
    (directory / 'output/document.json').write_text(json.dumps(document))
    manifest = {key: value for key, value in document.items() if key != 'blocks'}
    manifest['assets'] = [dict(assetId='document', path='document.json', mediaType='application/json')]
    (directory / 'output/manifest.json').write_text(json.dumps(manifest))
    validate_output(directory, options)
    print('Server accepted single block chars:', len(document['blocks'][0]['text']))
    print('Client read_material_parse_blocks per-call text cap: 24000; count=1 still cannot read this block')
