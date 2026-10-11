"""Executed once in an offline, restricted container; no credentials are mounted."""
import hashlib
import json
import os
import sys
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath

MAX_OUTPUT = 64 * 1024 * 1024
MAX_TEXT = 1_000_000


class TableText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []
        self.hidden = 0
        self.first_cell = True

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style"}:
            self.hidden += 1
        elif not self.hidden and tag == "tr":
            self.first_cell = True
        elif not self.hidden and tag in {"td", "th"}:
            if not self.first_cell:
                self.parts.append("\t")
            self.first_cell = False

    def handle_endtag(self, tag):
        if tag in {"script", "style"} and self.hidden:
            self.hidden -= 1
        elif tag == "tr" and not self.hidden:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def content_text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "".join(content_text(v.get("content", "")) for v in value if isinstance(v, dict))
    return ""


def normalize_pages(raw_pages, selected):
    blocks, pages, blank = [], [], []
    for page in raw_pages:
        number = int(page["page_idx"]) + 1
        if number not in selected or number in pages:
            raise ValueError("invalid_page_mapping")
        pages.append(number)
        page_blocks = page.get("blocks", [])
        if not page_blocks:
            blank.append(number)

        def visit(block, ordinal):
            kind = block.get("type", "unknown")
            content = block.get("content", "")
            # Visual parents and lists contain child blocks; inline spans are flattened.
            if kind in {"image", "table", "chart", "code", "list", "index"} and isinstance(content, list):
                for child_index, child in enumerate(content):
                    visit(child, f"{ordinal}.{child_index}")
                return
            text = content_text(content)
            if kind == "table_body":
                parser = TableText()
                parser.feed(text)
                text = "".join(parser.parts).strip("\n")
            entry = {"blockId": f"p{number}-b{ordinal}", "page": number, "type": kind, "text": text}
            if kind == "equation":
                entry["latex"] = text
            if block.get("image_path"):
                entry["imagePath"] = block["image_path"]
            # Upstream bbox needs coordinate metadata before exposing precise geometry.
            blocks.append(entry)

        for i, block in enumerate(page_blocks):
            visit(block, str(i))
    return blocks, pages, blank


def run(input_dir=Path("/input"), output_dir=Path("/output")):
    options = json.loads((input_dir / "request.json").read_text())
    source = input_dir / ("source." + options["extension"])
    if hashlib.sha256(source.read_bytes()).hexdigest() != options["sourceSha256"]:
        raise ValueError("source_digest_mismatch")
    start, end = options["pages"]
    if options["tier"] != "basic" or not 1 <= start <= end <= 200 or end - start >= 5:
        raise ValueError("invalid_request")
    if options["extension"] == "pdf":
        from pypdf import PdfReader
        reader = PdfReader(source)
        if reader.is_encrypted:
            raise ValueError("encrypted_pdf")
        total_pages = len(reader.pages)
        if total_pages > 200 or end > total_pages:
            raise ValueError("page_limit")
    else:
        from PIL import Image
        Image.MAX_IMAGE_PIXELS = 32_000_000
        with Image.open(source) as image:
            if image.width * image.height > 32_000_000:
                raise ValueError("image_limit")
            image.verify()
        total_pages = 1
    from mineru import parse
    from mineru.parser.writer import DataWriter

    class SafeWriter(DataWriter):
        count = 0
        size = 0

        def write(self, name, data):
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name:
                raise ValueError("unsafe_asset_path")
            self.count += 1
            self.size += len(data)
            if self.count > 2000 or self.size > MAX_OUTPUT:
                raise ValueError("output_limit")
            target = output_dir / "raw" / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)

    result = parse(source, tier="basic", page_range=f"{start}-{end}" if options["extension"] == "pdf" else "", image_analysis=False)
    result.save(SafeWriter())
    raw = json.loads((output_dir / "raw" / "middle_json.json").read_text(encoding="utf-8"))
    blocks, processed, blank = normalize_pages(raw["pages"], list(range(start, end + 1)))
    if sum(len(b["text"]) for b in blocks) > MAX_TEXT:
        raise ValueError("text_limit")
    assets = []
    for block in blocks:
        image_path = block.pop("imagePath", None)
        if image_path:
            path = PurePosixPath(image_path)
            if path.is_absolute() or ".." in path.parts or "\\" in image_path or ":" in image_path:
                raise ValueError("unsafe_asset_path")
            image = output_dir / "raw" / path
            from PIL import Image
            with Image.open(image) as decoded:
                if decoded.width * decoded.height > 32_000_000:
                    raise ValueError("image_limit")
                decoded.verify()
            asset_id = "image-" + hashlib.sha256(image_path.encode()).hexdigest()[:24]
            block["assetId"] = asset_id
            if not any(a["assetId"] == asset_id for a in assets):
                # Re-encode removes metadata and establishes one safe delivered image type.
                target = output_dir / f"{asset_id}.png"
                with Image.open(image) as decoded:
                    decoded.convert("RGB").save(target, "PNG")
                assets.append({"assetId": asset_id, "path": target.name, "mediaType": "image/png"})
    missing = sorted(set(range(start, end + 1)) - set(processed))
    document = {"contractVersion": 1, "sourceSha256": options["sourceSha256"], "resultRevision": 1,
                "parserVersion": "4.0.10", "adapterVersion": "1", "tier": "basic", "totalPages": total_pages,
                "requestedRange": [start, end], "processedPages": processed, "blankPages": blank,
                "failedPages": missing, "complete": not missing and start == 1 and end == total_pages,
                "warnings": ["OCR and formula recognition require checking the original page; geometry unavailable."] + (["missing_pages"] if missing else []),
                "blocks": blocks}
    target = output_dir / "document.json"
    target.write_text(json.dumps(document, ensure_ascii=False), encoding="utf-8")
    assets.insert(0, {"assetId": "document", "path": "document.json", "mediaType": "application/json"})
    text = "\n\n".join(f"[第 {b['page']} 页 · {b['blockId']}]\n{b['text']}" for b in blocks)
    (output_dir / "content.txt").write_text(text, encoding="utf-8")
    assets.append({"assetId": "text", "path": "content.txt", "mediaType": "text/plain"})
    manifest = {k: v for k, v in document.items() if k != "blocks"}
    manifest["assets"] = assets
    (output_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    try:
        run()
    except Exception as exc:
        known = {"encrypted_pdf", "page_limit", "image_limit", "output_limit", "text_limit", "invalid_page_mapping", "source_digest_mismatch"}
        code = str(exc) if str(exc) in known else "parse_failed"
        Path("/output/error.json").write_text(json.dumps({"errorCode": code}))
        sys.exit(1)
