import hashlib
import json
import os
import shutil
import struct
import re
from pathlib import PurePosixPath

def validate_output(directory, options):
    manifest_file = directory / "output" / "manifest.json"
    if not manifest_file.is_file() or manifest_file.is_symlink() or manifest_file.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("invalid_manifest")
    manifest = json.loads(manifest_file.read_text())
    if (manifest["sourceSha256"] != options["sourceSha256"] or manifest["requestedRange"] != options["pages"]
            or manifest["tier"] != "basic" or manifest["parserVersion"] != "4.0.10" or manifest["contractVersion"] != 1):
        raise ValueError("invalid_manifest")
    selected = set(range(options["pages"][0], options["pages"][1] + 1))
    processed = manifest["processedPages"]
    if len(processed) != len(set(processed)) or not set(processed) <= selected or set(manifest["failedPages"]) != selected - set(processed):
        raise ValueError("invalid_coverage")
    if not 1 <= manifest["totalPages"] <= 200 or options["pages"][1] > manifest["totalPages"]:
        raise ValueError("invalid_coverage")
    if manifest["complete"] != (not manifest["failedPages"] and options["pages"] == [1, manifest["totalPages"]]):
        raise ValueError("invalid_coverage")
    assets = manifest["assets"]
    if len(assets) > 2000 or len({a["assetId"] for a in assets}) != len(assets):
        raise ValueError("invalid_assets")
    staging = directory / "publishing"
    staging.mkdir(exist_ok=True)
    size = 0
    for asset in assets:
        if not re.fullmatch(r"[a-z0-9-]{1,64}", asset["assetId"]):
            raise ValueError("invalid_asset_id")
        relative = PurePosixPath(asset["path"])
        if relative.is_absolute() or len(relative.parts) != 1 or ".." in relative.parts or ":" in str(relative) or "\\" in str(relative):
            raise ValueError("unsafe_path")
        source = directory / "output" / str(relative)
        if source.is_symlink() or not source.is_file():
            raise ValueError("missing_asset")
        size += source.stat().st_size
        if size > 64 * 1024 * 1024:
            raise ValueError("output_limit")
        if asset["mediaType"] == "image/png":
            # Full decoding happens in a separate offline validation container.
            with source.open("rb") as stream:
                prefix = stream.read(24)
            if len(prefix) != 24 or prefix[:8] != b"\x89PNG\r\n\x1a\n":
                raise ValueError("invalid_image")
            width, height = struct.unpack(">II", prefix[16:24])
            if not width or not height or width * height > 32_000_000:
                raise ValueError("image_limit")
        elif asset["mediaType"] not in {"text/plain", "application/json"}:
            raise ValueError("invalid_asset_type")
        if asset["assetId"] == "document" and source.stat().st_size > 8 * 1024 * 1024:
            raise ValueError("document_limit")
        target = staging / source.name
        shutil.copyfile(source, target)
        asset["size"] = target.stat().st_size
        asset["sha256"] = hashlib.sha256(target.read_bytes()).hexdigest()
    document = json.loads((staging / "document.json").read_text())
    for field in ("contractVersion", "tier", "parserVersion", "adapterVersion", "resultRevision", "totalPages", "requestedRange", "processedPages", "blankPages", "failedPages", "complete"):
        if document.get(field) != manifest.get(field):
            raise ValueError("document_manifest_mismatch")
    if document["sourceSha256"] != options["sourceSha256"] or len(document["blocks"]) > 10000:
        raise ValueError("invalid_document")
    if sum(len(b["text"]) for b in document["blocks"]) > 1_000_000:
        raise ValueError("text_limit")
    ids = {a["assetId"] for a in assets}
    if any(b["page"] not in processed or ("assetId" in b and b["assetId"] not in ids) for b in document["blocks"]):
        raise ValueError("invalid_block")
    os.replace(staging, directory / "published")
    return manifest

