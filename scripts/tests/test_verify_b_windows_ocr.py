import copy
import importlib.util
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location('b_windows_ocr', Path(__file__).resolve().parents[1] / 'verify_b_windows_ocr.py')
ocr = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ocr)


def sample():
    image = {'page': 1, 'imageSha256': 'b' * 64, 'width': 100, 'height': 200, 'rasterIsWhite': False}
    raw = {'engine': 'Windows.Media.Ocr', 'execution': 'ACTUAL_LOCAL_ENGINE', 'sourceSha256': 'a' * 64,
           'osVersion': 'test-version', 'pages': [{'page': 1, 'imageSha256': 'b' * 64, 'width': 100, 'height': 200, 'lines': []}]}
    return raw, [image]


def test_no_recognized_words_on_nonwhite_scan_is_not_a_blank_page_claim():
    raw, images = sample()
    result = ocr.normalize(raw, 'a' * 64, 1, 1, 1, images)
    assert result['processedPages'] == [1] and result['blankPages'] == []
    assert result['blocks'] == [] and result['failedPages'] == []


def test_actual_white_page_without_words_is_blank_but_words_override_white_flag():
    raw, images = sample()
    images[0]['rasterIsWhite'] = True
    assert ocr.normalize(raw, 'a' * 64, 1, 1, 1, images)['blankPages'] == [1]
    raw['pages'][0]['lines'] = [{'text': 'hallucinated text on white page'}]
    assert ocr.normalize(raw, 'a' * 64, 1, 1, 1, images)['blankPages'] == []


@pytest.mark.parametrize('field,value', [('engine', 'synthetic'), ('execution', 'NOT_RUN'), ('sourceSha256', 'c' * 64)])
def test_wrong_engine_receipt_or_source_binding_is_rejected(field, value):
    raw, images = sample()
    raw[field] = value
    with pytest.raises(ValueError, match='engine_receipt_mismatch'):
        ocr.normalize(raw, 'a' * 64, 1, 1, 1, images)


@pytest.mark.parametrize('field,value', [('imageSha256', 'c' * 64), ('width', 101), ('height', 201)])
def test_rendered_page_identity_cannot_be_replaced_by_engine_output(field, value):
    raw, images = sample()
    raw['pages'][0][field] = value
    with pytest.raises(ValueError, match='engine_image_binding_mismatch'):
        ocr.normalize(raw, 'a' * 64, 1, 1, 1, images)


@pytest.mark.parametrize('numbers', [[2], [1, 1], []])
def test_missing_duplicated_or_reordered_page_mapping_is_rejected(numbers):
    raw, images = sample()
    page = raw['pages'][0]
    raw['pages'] = [dict(copy.deepcopy(page), page=number) for number in numbers]
    with pytest.raises(ValueError, match='engine_page_mapping_mismatch'):
        ocr.normalize(raw, 'a' * 64, 1, 1, 1, images)


def test_oversized_line_is_rejected_not_silently_truncated():
    raw, images = sample()
    raw['pages'][0]['lines'] = [{'text': 'x' * 10001}]
    with pytest.raises(ValueError, match='engine_text_limit'):
        ocr.normalize(raw, 'a' * 64, 1, 1, 1, images)


def test_explicit_ink_crop_preserves_source_and_records_coordinate_mapping():
    from PIL import Image, ImageDraw
    source = Image.new('RGB', (800, 1200), 'white')
    ImageDraw.Draw(source).rectangle((100, 150, 400, 260), fill='black')
    before = source.tobytes()
    candidate, transform = ocr.recognition_image(source, 'ink-crop')
    try:
        assert source.tobytes() == before
        assert transform['cropBox'] == [68, 118, 433, 293]
        assert candidate.width <= 2000 and max(transform['scaleX'], transform['scaleY']) <= 3
        assert transform['characterSubstitution'] is False
        assert candidate.size == (1095, 525)
    finally:
        candidate.close();source.close()


def test_white_page_is_not_upscaled_into_hallucinated_content_and_none_is_identity():
    from PIL import Image
    source = Image.new('RGB', (100, 200), 'white')
    for mode in ('none', 'ink-crop'):
        candidate, transform = ocr.recognition_image(source, mode)
        try:
            assert candidate.tobytes() == source.tobytes()
            assert transform['cropBox'] == [0, 0, 100, 200]
            assert transform['scaleX'] == transform['scaleY'] == 1
        finally:
            candidate.close()
    source.close()
