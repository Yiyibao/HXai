"""Build original raster scans and score document-service v1 transcriptions offline.

No OCR engine, upload, model call or authorization is performed by this CLI.
A valid comparison is not evidence that a recognition engine ran or a task succeeded.
"""
import argparse
import hashlib
import json
from pathlib import Path
from io import BytesIO
import re
import sys
import unicodedata
import zlib

VERSION = 1
MAX_CHARS = 2500  # Per page, bounding quadratic edit alignment work and memory.
MAX_PAGES = 5    # Existing Basic parse request limit; this is a small evaluation corpus.
SHA256 = re.compile(r"[0-9a-f]{64}")


def require(condition, code):
    if not condition:
        raise ValueError(code)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load_json(path, maximum):
    with Path(path).open("rb") as stream:
        raw = stream.read(maximum + 1)
    require(len(raw) <= maximum, "json_limit")
    def unique(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, "duplicate_json_key")
            value[key] = item
        return value
    return json.loads(raw, object_pairs_hook=unique,
                      parse_constant=lambda _: require(False, "nonfinite_json")), raw


def canonical(text):
    # Literal transcription only: never fold signs, scripts, case or math notation.
    return "".join(c for c in unicodedata.normalize("NFC", text) if not c.isspace())


def page_numbers(value, selected):
    require(isinstance(value, list) and all(type(n) is int and n in selected for n in value), "invalid_page_mapping")
    require(len(value) == len(set(value)), "duplicate_page")
    return set(value)


def error_kind(expected, actual):
    pair = {expected, actual}
    for name, group in [("one_ell", set("1lI")), ("multiplication", set("x×*")),
                        ("sign", set("−-+")), ("script", set("0123456789₀₁₂₃₄₅₆₇₈₉⁰¹²³⁴⁵⁶⁷⁸⁹"))]:
        if pair <= group:
            return name
    return "other"


def edit_counts(expected, actual):
    """Unit-cost codepoint edit distance; ties: diagonal, deletion, insertion.

    Two distance rows plus one byte per traceback cell; no text goes into reports.
    Indices refer to the NFC/non-whitespace streams, not raw PDF coordinates.
    """
    require(max(len(expected), len(actual)) <= MAX_CHARS, "page_text_limit")
    width = len(actual) + 1
    trace = [bytearray(width) for _ in range(len(expected) + 1)]
    previous = list(range(width))
    trace[0][1:] = b"\x02" * len(actual)
    for i, left in enumerate(expected, 1):
        current = [i] + [0] * len(actual)
        trace[i][0] = 1
        for j, right in enumerate(actual, 1):
            diagonal = previous[j - 1] + (left != right)
            deletion = previous[j] + 1
            insertion = current[j - 1] + 1
            cost = min(diagonal, deletion, insertion)
            current[j] = cost
            trace[i][j] = 0 if diagonal == cost else (1 if deletion == cost else 2)
        previous = current
    counts = {"substitutions": 0, "insertions": 0, "deletions": 0}
    confusions = {}
    locations = []
    i, j = len(expected), len(actual)
    while i or j:
        direction = trace[i][j]
        if direction == 0:
            i -= 1
            j -= 1
            if expected[i] == actual[j]:
                continue
            operation = "substitutions"
            kind = error_kind(expected[i], actual[j])
            confusions[kind] = confusions.get(kind, 0) + 1
        elif direction == 1:
            i -= 1
            operation = "deletions"
        else:
            j -= 1
            operation = "insertions"
        counts[operation] += 1
        if len(locations) < 32:
            locations.append({"operation": operation, "referenceIndex": i, "candidateIndex": j})
    return {**counts, "distance": previous[-1], "confusions": confusions,
            "errorLocations": list(reversed(locations)), "locationsTruncated": sum(counts.values()) > 32}


def validate_truth(truth):
    require(isinstance(truth, dict) and type(truth.get("contractVersion")) is int and truth["contractVersion"] == VERSION, "invalid_truth")
    require(isinstance(truth.get("sourceSha256"), str) and SHA256.fullmatch(truth["sourceSha256"]), "invalid_truth")
    total = truth.get("totalPages")
    require(type(total) is int and 1 <= total <= MAX_PAGES, "invalid_truth")
    pages = truth.get("pages")
    require(isinstance(pages, list) and len(pages) == total, "invalid_truth")
    for number, page in enumerate(pages, 1):
        require(isinstance(page, dict) and type(page.get("page")) is int and page["page"] == number, "invalid_truth")
        require(type(page.get("ambiguous")) is bool and isinstance(page.get("text"), str), "invalid_truth")
        require(len(canonical(page["text"])) <= MAX_CHARS, "page_text_limit")
        require(not page["ambiguous"] or bool(page.get("ambiguity")), "invalid_truth")


def score_document(truth, document):
    """Compare a validated source-bound truth to a parser v1 document.

    Missing/failed selected pages remain in the character denominator; ambiguous
    pages are always excluded and retain a separate confirmation obligation.
    """
    validate_truth(truth)
    require(isinstance(document, dict), "invalid_document")
    require(type(document.get("contractVersion")) is int and document["contractVersion"] == 1, "invalid_document")
    require(document.get("sourceSha256") == truth["sourceSha256"], "source_digest_mismatch")
    require(type(document.get("totalPages")) is int and document["totalPages"] == truth["totalPages"], "invalid_coverage")
    for field in ("parserVersion", "adapterVersion"):
        require(isinstance(document.get(field), str) and 0 < len(document[field]) <= 128, "invalid_document")
    require(type(document.get("resultRevision")) is int and document["resultRevision"] >= 1, "invalid_document")
    require(document.get("tier") == "basic", "invalid_document")
    interval = document.get("requestedRange")
    require(isinstance(interval, list) and len(interval) == 2 and all(type(n) is int for n in interval), "invalid_coverage")
    start, end = interval
    require(1 <= start <= end <= truth["totalPages"], "invalid_coverage")
    selected = set(range(start, end + 1))
    processed = page_numbers(document.get("processedPages"), selected)
    failed = page_numbers(document.get("failedPages"), selected)
    blank = page_numbers(document.get("blankPages"), processed)
    require(not (processed & failed) and processed | failed == selected, "invalid_coverage")
    require(type(document.get("complete")) is bool and document["complete"] ==
            (not failed and selected == set(range(1, truth["totalPages"] + 1))), "false_complete")
    blocks = document.get("blocks")
    require(isinstance(blocks, list) and len(blocks) <= 10000, "invalid_blocks")
    per_page = {page: [] for page in selected}
    ids = set()
    total_chars = 0
    for block in blocks:
        require(isinstance(block, dict) and type(block.get("page")) is int and block["page"] in processed, "invalid_block_page")
        identifier = block.get("blockId")
        require(isinstance(identifier, str) and 0 < len(identifier) <= 128 and identifier not in ids, "invalid_block_id")
        ids.add(identifier)
        require(isinstance(block.get("text"), str), "invalid_block_text")
        latex = block.get("latex")
        require(latex is None or isinstance(latex, str), "invalid_block_latex")
        total_chars += len(block["text"]) + len(latex or "")
        require(total_chars <= 100000, "text_limit")
        # Match MathWorkCandidates exactly: the reviewed candidate uses nonblank
        # LaTeX, otherwise text. This remains literal scoring, never equivalence.
        candidate = latex if latex is not None and latex.strip() else block["text"]
        per_page[block["page"]].append(candidate)
    for page in blank:
        require(not canonical("".join(per_page[page])), "blank_with_content")
    rows = []
    totals = {"referenceCharacters": 0, "substitutions": 0, "insertions": 0, "deletions": 0, "distance": 0}
    for number in range(start, end + 1):
        reference = truth["pages"][number - 1]
        actual = canonical("\n".join(per_page[number]))
        require(len(actual) <= MAX_CHARS, "page_text_limit")
        row = {"physicalPage": number, "processed": number in processed, "failed": number in failed,
               "declaredBlank": number in blank, "requiresConfirmation": reference["ambiguous"]}
        if reference["ambiguous"]:
            row.update({"characterScore": "NOT_EVALUATED_AMBIGUOUS", "referenceCharacters": None})
        else:
            expected = canonical(reference["text"])
            errors = edit_counts(expected, actual)
            row.update({"characterScore": "SCORED", "referenceCharacters": len(expected), **errors,
                        "characterErrorRate": errors["distance"] / len(expected) if expected else None,
                        "blankHallucination": not expected and bool(actual),
                        "falseBlank": bool(expected) and number in blank})
            for key in totals:
                totals[key] += row[key]
        rows.append(row)
    totals["characterErrorRate"] = totals["distance"] / totals["referenceCharacters"] if totals["referenceCharacters"] else None
    return {"contractVersion": VERSION, "evaluatorRevision": 2, "evaluationStatus": "PASS", "claimScope": "LOCAL_LITERAL_TRANSCRIPTION_COMPARISON_ONLY",
            "recognitionExecution": "NOT_ATTESTED", "mathematicsStatus": "NOT_EVALUATED", "taskCompletion": "NOT_EVALUATED",
            "layoutStatus": "NOT_EVALUATED", "sourceSha256": truth["sourceSha256"],
            "parserVersion": document["parserVersion"], "adapterVersion": document["adapterVersion"],
            "resultRevision": document["resultRevision"], "requestedRange": interval,
            "coverage": {"processedPages": sorted(processed), "failedPages": sorted(failed),
                         "requestedPages": len(selected), "processedFraction": len(processed) / len(selected),
                         "entireSourceRequested": len(selected) == truth["totalPages"],
                         "unrequestedPages": sorted(set(range(1, truth["totalPages"] + 1)) - selected)},
            "normalization": "UNICODE_NFC_REMOVE_WHITESPACE_ONLY; no mathematical equivalence or LaTeX canonicalization",
            "candidateProjection": "NONBLANK_LATEX_ELSE_TEXT; matches MathWorkCandidates",
            "totals": totals, "pages": rows}


def write_json_new(path, value):
    with Path(path).open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def font_runs(text, coverage):
    """Select only fonts with an actual glyph; never publish missing-glyph boxes."""
    runs = []
    for char in text:
        index = next((i for i, cmap in enumerate(coverage) if ord(char) in cmap), None)
        require(index is not None, "missing_font_glyph")
        if runs and runs[-1][1] == index:
            runs[-1] = (runs[-1][0] + char, index)
        else:
            runs.append((char, index))
    return runs


def check_pdf(source, expected_hash, total):
    from pypdf import PdfReader
    from pypdf.errors import PyPdfError
    with Path(source).open("rb") as stream:
        raw = stream.read(30 * 1024 * 1024 + 1)
    require(len(raw) <= 30 * 1024 * 1024, "source_limit")
    require(digest(raw) == expected_hash, "source_digest_mismatch")
    try:
        reader = PdfReader(BytesIO(raw), strict=True)
    except PyPdfError:
        raise ValueError("invalid_pdf") from None
    require(not reader.is_encrypted and len(reader.pages) == total, "source_page_mismatch")
    return reader


def build_corpus(spec_path, output, font_path, symbol_font_path=None):
    from PIL import Image, ImageDraw, ImageFont, __version__ as pillow_version
    from fontTools.ttLib import TTFont
    from pypdf import PdfWriter, __version__ as pypdf_version
    from pypdf.generic import DecodedStreamObject, NameObject, NumberObject, DictionaryObject
    spec, raw_spec = load_json(spec_path, 65536)
    require(spec.get("contractVersion") == VERSION and 1 <= len(spec["pages"]) <= MAX_PAGES, "invalid_corpus")
    font_paths = [font_path] + ([symbol_font_path] if symbol_font_path else [])
    fonts = [ImageFont.truetype(str(path), 36) for path in font_paths]
    coverage = []
    for path in font_paths:
        with TTFont(path, fontNumber=0) as face:
            coverage.append({point for point, glyph in face.getBestCmap().items() if glyph != ".notdef"})
    # Fail before creating output if any source symbol cannot be drawn.
    for definition in spec["pages"]:
        for text in definition["lines"]:
            font_runs(text.replace("\t", "        "), coverage)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    writer = PdfWriter()
    truth_pages, artifacts = [], {}
    for number, definition in enumerate(spec["pages"], 1):
        require(type(definition["page"]) is int and definition["page"] == number, "invalid_corpus")
        lines = definition["lines"]
        require(isinstance(lines, list) and len(lines) <= 16 and all(isinstance(s, str) for s in lines), "invalid_corpus")
        image = Image.new("RGB", (1240, 1754), "white")
        draw = ImageDraw.Draw(image)
        for line_index, text in enumerate(lines):
            rendered = text.replace("\t", "        ")
            runs = font_runs(rendered, coverage)
            widths = [draw.textlength(part, font=fonts[index]) for part, index in runs]
            require(sum(widths) <= 1080, "render_line_limit")
            x = 80
            for (part, index), width in zip(runs, widths):
                draw.text((x, 148 + line_index * 90), part, font=fonts[index], fill="black", anchor="ls")
                x += width
        if definition["ambiguous"]:
            # Deliberately featureless synthetic pen stroke; not real handwriting.
            draw.line((210, 110, 210, 145), fill="black", width=3)
        png = output / f"page-{number:02d}.png"
        image.save(png)
        artifacts[png.name] = digest(png.read_bytes())
        page = writer.add_blank_page(width=620, height=877)
        pixels = DecodedStreamObject()
        pixels.set_data(zlib.compress(image.tobytes(), level=9))
        pixels.update({NameObject("/Type"): NameObject("/XObject"), NameObject("/Subtype"): NameObject("/Image"),
                       NameObject("/Width"): NumberObject(image.width), NameObject("/Height"): NumberObject(image.height),
                       NameObject("/ColorSpace"): NameObject("/DeviceRGB"), NameObject("/BitsPerComponent"): NumberObject(8),
                       NameObject("/Filter"): NameObject("/FlateDecode")})
        page[NameObject("/Resources")] = DictionaryObject({NameObject("/XObject"): DictionaryObject({NameObject("/Scan"): writer._add_object(pixels)})})
        content = DecodedStreamObject()
        content.set_data(b"q 620 0 0 877 0 0 cm /Scan Do Q")
        page[NameObject("/Contents")] = writer._add_object(content)
        truth_pages.append({"page": number, "text": "\n".join(lines) if not definition["ambiguous"] else "",
                            "ambiguous": definition["ambiguous"], "ambiguity": definition.get("ambiguity"),
                            "kind": definition["kind"], "tags": definition["tags"], "imageSha256": artifacts[png.name]})
    source = output / "source.pdf"
    with source.open("xb") as stream:
        writer.write(stream)
    truth = {"contractVersion": VERSION, "corpusId": spec["corpusId"], "provenance": spec["provenance"],
             "sourceSha256": digest(source.read_bytes()), "totalPages": len(truth_pages), "pages": truth_pages}
    validate_truth(truth)
    reader = check_pdf(source, truth["sourceSha256"], truth["totalPages"])
    for page in reader.pages:
        require(not page.extract_text().strip() and len(page.images) == 1, "not_raster_only")
    write_json_new(output / "truth.json", truth)
    artifacts.update({"source.pdf": truth["sourceSha256"], "truth.json": digest((output / "truth.json").read_bytes())})
    write_json_new(output / "CORPUS_SEAL.json", {"contractVersion": VERSION, "specSha256": digest(raw_spec),
                   "scriptSha256": digest(Path(__file__).read_bytes()), "fontSha256": digest(Path(font_path).read_bytes()),
                   "symbolFontSha256": digest(Path(symbol_font_path).read_bytes()) if symbol_font_path else None,
                   "pillowVersion": pillow_version, "pypdfVersion": pypdf_version, "artifacts": artifacts,
                   "recognitionExecution": "NOT_RUN", "syntheticOnly": True, "fontRedistributed": False})
    return {"status": "PASS", "physicalPages": len(truth_pages), "sourceSha256": truth["sourceSha256"], "recognitionExecution": "NOT_RUN"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build", help="Create a new local raster-only synthetic corpus; never run OCR")
    build.add_argument("--spec", type=Path, required=True)
    build.add_argument("--out", type=Path, required=True)
    build.add_argument("--font", type=Path, required=True)
    build.add_argument("--symbol-font", type=Path, help="Explicit installed fallback for missing math glyphs; no font is downloaded")
    score = commands.add_parser("score", help="Compare a source-bound parser v1 document to fixed truth; no engine attestation")
    score.add_argument("--truth", type=Path, required=True)
    score.add_argument("--source", type=Path, required=True)
    score.add_argument("--document", type=Path, required=True)
    score.add_argument("--output", type=Path, required=True)
    score.add_argument("--result-origin", choices=["synthetic-control", "external-unattested"], default="external-unattested")
    args = parser.parse_args()
    try:
        if args.command == "build":
            result = build_corpus(args.spec, args.out, args.font, args.symbol_font)
        else:
            truth, truth_raw = load_json(args.truth, 65536)
            validate_truth(truth)
            check_pdf(args.source, truth["sourceSha256"], truth["totalPages"])
            document, document_raw = load_json(args.document, 8 * 1024 * 1024)
            result = score_document(truth, document)
            result.update({"truthSha256": digest(truth_raw), "documentSha256": digest(document_raw),
                           "scriptSha256": digest(Path(__file__).read_bytes()), "declaredResultOrigin": args.result_origin})
            write_json_new(args.output, result)
            result = {"evaluationStatus": result["evaluationStatus"], "totals": result["totals"],
                      "recognitionExecution": result["recognitionExecution"], "declaredResultOrigin": args.result_origin}
        print(json.dumps(result, ensure_ascii=True))
    except (ValueError, KeyError, TypeError, OSError, RecursionError) as exc:
        # Do not echo parser text, filesystem paths or provider messages to logs.
        code = str(exc) if type(exc) is ValueError and re.fullmatch(r"[a-z_]{1,64}", str(exc)) else "invalid_input_or_output"
        print(json.dumps({"evaluationStatus": "FAIL", "errorCode": code}), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
