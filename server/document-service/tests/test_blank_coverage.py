import json

import pytest

from api.output import validate_output


def output(tmp_path, *, blank=None, blocks=None, processed=None, failed=None):
    target = tmp_path / 'output'
    target.mkdir()
    manifest = {'contractVersion': 1, 'sourceSha256': 'a' * 64, 'tier': 'basic',
                'parserVersion': '4.0.10', 'adapterVersion': '1', 'resultRevision': 1,
                'requestedRange': [1, 2], 'totalPages': 2, 'processedPages': [1, 2] if processed is None else processed,
                'failedPages': [] if failed is None else failed, 'blankPages': [2] if blank is None else blank,
                'complete': not failed, 'warnings': [],
                'assets': [{'assetId': 'document', 'path': 'document.json', 'mediaType': 'application/json'}]}
    document = {k: v for k, v in manifest.items() if k != 'assets'}
    document['blocks'] = [{'blockId': 'p1-b0', 'page': 1, 'type': 'text', 'text': 'Recognized source text'}] if blocks is None else blocks
    (target / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
    (target / 'document.json').write_text(json.dumps(document), encoding='utf-8')
    return {'sourceSha256': 'a' * 64, 'pages': [1, 2]}


@pytest.mark.parametrize('case', ['recognized_text', 'recognized_formula', 'unprocessed_blank', 'duplicate_blank'])
def test_inconsistent_blank_coverage_is_not_published(tmp_path, case):
    kwargs = {}
    if case == 'recognized_text':
        kwargs['blocks'] = [{'blockId': 'p2-b0', 'page': 2, 'type': 'text', 'text': 'This page has content'}]
    elif case == 'recognized_formula':
        kwargs['blocks'] = [{'blockId': 'p2-b0', 'page': 2, 'type': 'equation', 'text': '', 'latex': 'x^2'}]
    elif case == 'unprocessed_blank':
        kwargs.update(processed=[1], failed=[2])
    else:
        kwargs['blank'] = [2, 2]
    options = output(tmp_path, **kwargs)
    with pytest.raises(ValueError):
        validate_output(tmp_path, options)
    assert not (tmp_path / 'published').exists()


def test_empty_blank_page_keeps_real_text_on_other_processed_page(tmp_path):
    options = output(tmp_path)
    manifest = validate_output(tmp_path, options)
    document = json.loads((tmp_path / 'published/document.json').read_text())
    assert manifest['processedPages'] == [1, 2] and manifest['blankPages'] == [2]
    assert document['blocks'] == [{'blockId': 'p1-b0', 'page': 1, 'type': 'text', 'text': 'Recognized source text'}]
