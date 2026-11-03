import copy
import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location("ocr_corpus", Path(__file__).parents[1] / "verify_b_ocr_corpus.py")
ocr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ocr)


def sample():
    truth = {"contractVersion": 1, "sourceSha256": "a" * 64, "totalPages": 3,
             "pages": [{"page": 1, "text": "x²−1=0", "ambiguous": False},
                       {"page": 2, "text": "x₁=1", "ambiguous": False},
                       {"page": 3, "text": "", "ambiguous": False}]}
    document = {"contractVersion": 1, "sourceSha256": "a" * 64, "totalPages": 3,
                "parserVersion": "4.0.10", "adapterVersion": "1", "resultRevision": 1, "tier": "basic",
                "requestedRange": [1, 3], "processedPages": [1, 2, 3], "blankPages": [3],
                "failedPages": [], "complete": True,
                "blocks": [{"blockId": "p1-b0", "page": 1, "text": "x²−1=0", "type": "equation"},
                           {"blockId": "p2-b0", "page": 2, "text": "x₁=1", "type": "equation"}]}
    return truth, document


@pytest.mark.parametrize('block', [
    {'type': 'equation', 'text': '', 'latex': 'x=2'},
    {'type': 'equation', 'text': ''},
    {'type': 'image', 'text': '', 'assetId': 'original-image'},
    {'type': 'table', 'text': ''},
])
def test_blank_page_cannot_hide_nontext_content_behind_empty_text(block):
    truth, document = sample()
    document['blocks'].append(dict(block, blockId='nontext', page=3))
    with pytest.raises(ValueError, match='blank_with_content'):
        ocr.score_document(truth, document)


def test_missing_page_is_deletion_not_denominator_reduction():
    truth, document = sample()
    document.update(processedPages=[1, 3], failedPages=[2], complete=False)
    document["blocks"] = document["blocks"][:1]
    result = ocr.score_document(truth, document)
    assert result["totals"] == {"referenceCharacters": 10, "substitutions": 0, "insertions": 0,
                                "deletions": 4, "distance": 4, "characterErrorRate": 0.4}
    assert result["coverage"]["processedFraction"] == 2 / 3


def test_latex_candidate_is_scored_like_the_product_not_silently_replaced_by_text():
    truth, document = sample()
    # Product MathWorkCandidates chooses nonblank latex over text. The scorer must
    # expose that wrong candidate, even when the parallel plain text looks perfect.
    document['blocks'][0]['latex'] = 'x2+1=0'
    result = ocr.score_document(truth, document)
    assert result['pages'][0]['distance'] == 2


def test_formula_on_declared_blank_page_cannot_disappear_from_score():
    truth, document = sample()
    document['blocks'].append({'blockId': 'p3-formula', 'page': 3, 'type': 'equation', 'text': '', 'latex': 'x=1'})
    with pytest.raises(ValueError, match='^blank_with_content$'):
        ocr.score_document(truth, document)


@pytest.mark.parametrize('latex', [None, '', '   '])
def test_absent_or_whitespace_latex_keeps_literal_plain_text(latex):
    truth, document = sample()
    document['blocks'][0]['latex'] = latex
    assert ocr.score_document(truth, document)['totals']['distance'] == 0


@pytest.mark.parametrize('latex', [True, 2, [], {}])
def test_invalid_formula_candidate_type_rejected(latex):
    truth, document = sample()
    document['blocks'][0]['latex'] = latex
    with pytest.raises(ValueError, match='^invalid_block_latex$'):
        ocr.score_document(truth, document)


@pytest.mark.parametrize('raw', [b'{"source":1,"source":2}', b'{"value":NaN}', b'{"value":Infinity}'])
def test_ambiguous_json_input_is_rejected(tmp_path, raw):
    path = tmp_path / 'ambiguous.json'
    path.write_bytes(raw)
    with pytest.raises(ValueError):
        ocr.load_json(path, 65536)


def test_legitimate_partial_request_not_mislabeled_complete_source():
    truth, document = sample()
    document.update(requestedRange=[2, 2], processedPages=[2], blankPages=[], complete=False)
    document["blocks"] = document["blocks"][1:]
    result = ocr.score_document(truth, document)
    assert result["totals"]["referenceCharacters"] == 4
    assert result["totals"]["distance"] == 0
    assert result["coverage"]["unrequestedPages"] == [1, 3]
    assert result["coverage"]["entireSourceRequested"] is False


def test_blank_hallucination_contributes_insertions_even_with_zero_page_denominator():
    truth, document = sample()
    document["blankPages"] = []
    document["blocks"].append({"blockId": "p3-b0", "page": 3, "text": "fake"})
    result = ocr.score_document(truth, document)
    assert result["totals"]["insertions"] == 4
    assert result["totals"]["characterErrorRate"] == 0.4
    assert result["pages"][2]["blankHallucination"] is True
    assert result["pages"][2]["characterErrorRate"] is None


def test_false_blank_cannot_hide_nonblank_truth():
    truth, document = sample()
    document["blankPages"] = [2, 3]
    document["blocks"] = document["blocks"][:1]
    result = ocr.score_document(truth, document)
    assert result["pages"][1]["falseBlank"] is True
    assert result["totals"]["deletions"] == 4


def test_ambiguity_excluded_from_character_score_but_confirmation_never_cleared():
    truth, document = sample()
    truth["pages"][1].update(text="", ambiguous=True, ambiguity={"alternatives": ["1", "l"]})
    for candidate in ("x=1", "x=l", ""):
        document["blocks"][1]["text"] = candidate
        result = ocr.score_document(truth, document)
        assert result["totals"]["referenceCharacters"] == 6
        assert result["pages"][1]["characterScore"] == "NOT_EVALUATED_AMBIGUOUS"
        assert result["pages"][1]["requiresConfirmation"] is True


def test_unicode_and_whitespace_equivalence_allowed_but_not_mathematical_notation_folding():
    truth, document = sample()
    truth["pages"][0]["text"] = "é x²−1=0"
    document["blocks"][0]["text"] = "e\u0301\n x² − 1 = 0"
    assert ocr.score_document(truth, document)["totals"]["distance"] == 0
    document["blocks"][0]["text"] = "é x2-1=0"
    result = ocr.score_document(truth, document)
    assert result["pages"][0]["substitutions"] == 2
    assert result["pages"][0]["confusions"] == {"script": 1, "sign": 1}
    assert result["mathematicsStatus"] == "NOT_EVALUATED"


def test_swapped_physical_page_contents_are_not_matched_globally():
    truth, document = sample()
    first, second = document["blocks"]
    first["text"], second["text"] = second["text"], first["text"]
    result = ocr.score_document(truth, document)
    assert [row["distance"] for row in result["pages"]] == [4, 4, 0]


@pytest.mark.parametrize("updates,code", [
    ({"sourceSha256": "b" * 64}, "source_digest_mismatch"),
    ({"totalPages": 4}, "invalid_coverage"),
    ({"requestedRange": [True, 3]}, "invalid_coverage"),
    ({"processedPages": [1, 1, 2, 3]}, "duplicate_page"),
    ({"processedPages": [1, 2]}, "invalid_page_mapping"),
    ({"failedPages": [2]}, "invalid_coverage"),
    ({"blankPages": [4]}, "invalid_page_mapping"),
    ({"complete": False}, "false_complete"),
    ({"complete": 1}, "false_complete"),
    ({"resultRevision": True}, "invalid_document"),
])
def test_wrong_source_or_forged_coverage_rejected(updates, code):
    truth, document = sample()
    document.update(updates)
    with pytest.raises(ValueError, match="^" + code + "$"):
        ocr.score_document(truth, document)


@pytest.mark.parametrize("block", [
    {"blockId": "bad", "page": 0, "text": "x"},
    {"blockId": "bad", "page": True, "text": "x"},
    {"blockId": "p1-b0", "page": 1, "text": "duplicate"},
    {"blockId": "bad", "page": 1, "text": 123},
    {"blockId": "bad", "page": 3, "text": "nonblank"},
])
def test_invalid_blocks_do_not_grant_scores(block):
    truth, document = sample()
    document["blocks"].append(block)
    with pytest.raises(ValueError):
        ocr.score_document(truth, document)


def test_inputs_preserved_and_result_is_not_engine_or_task_attestation():
    truth, document = sample()
    original = copy.deepcopy((truth, document))
    result = ocr.score_document(truth, document)
    assert (truth, document) == original
    assert result["recognitionExecution"] == "NOT_ATTESTED"
    assert result["taskCompletion"] == "NOT_EVALUATED"
    assert result["totals"]["distance"] == 0


def test_alignment_counts_insert_delete_substitute_and_confusions_without_logging_text():
    assert {k: ocr.edit_counts("abc", "axc")[k] for k in ("distance", "substitutions")} == {"distance": 1, "substitutions": 1}
    assert ocr.edit_counts("abc", "ac")["deletions"] == 1
    assert ocr.edit_counts("abc", "abbc")["insertions"] == 1
    result = ocr.edit_counts("1x−²", "l×-2")
    assert result["confusions"] == {"one_ell": 1, "multiplication": 1, "sign": 1, "script": 1}
    assert result["distance"] == result["substitutions"] == 4
    assert result["errorLocations"] == [{"operation": "substitutions", "referenceIndex": i, "candidateIndex": i} for i in range(4)]


def test_page_limit_rejects_expensive_alignment_before_work():
    with pytest.raises(ValueError, match="page_text_limit"):
        ocr.edit_counts("x" * (ocr.MAX_CHARS + 1), "")


def test_existing_report_not_overwritten(tmp_path):
    report = tmp_path / "report.json"
    report.write_bytes(b"original receipt")
    with pytest.raises(FileExistsError):
        ocr.write_json_new(report, {"evaluationStatus": "PASS"})
    assert report.read_bytes() == b"original receipt"


def test_missing_font_symbol_is_rejected_instead_of_becoming_false_ocr_truth():
    with pytest.raises(ValueError, match="^missing_font_glyph$"):
        ocr.font_runs("x₁", [{ord("x")}])
    assert ocr.font_runs("x₁=1", [{ord(c) for c in "x=1"}, {ord("₁")}]) == [
        ("x", 0), ("₁", 1), ("=1", 0)]
