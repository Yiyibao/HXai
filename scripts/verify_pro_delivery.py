"""Read-only, case-sensitive verification of the Pro delivery manifest."""
import argparse
import hashlib
import json
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


def verify(root):
    manifest_path = root / "FILES_SHA256.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    actual = {
        p.relative_to(root).as_posix(): p
        for p in root.rglob("*") if p.is_file()
    }
    expected = manifest["files"]
    extras = sorted(set(actual) - set(expected) - {"FILES_SHA256.json"})
    extra_fingerprints = {
        name: (actual[name].stat().st_size, hashlib.sha256(actual[name].read_bytes()).hexdigest())
        for name in extras
    }
    records = []
    case_groups = defaultdict(list)
    for name, entry in expected.items():
        case_groups[name.casefold()].append(name)
        record = {"path": name, "expected": entry}
        # Enumerated names avoid Windows resolving two distinct manifest entries
        # to the same physical file on its case-insensitive filesystem.
        if name not in actual:
            record["status"] = "MISSING_PATH"
            record["content_matching_extra_paths"] = [
                extra for extra, fingerprint in extra_fingerprints.items()
                if fingerprint == (entry["bytes"], entry["sha256"])
            ]
        else:
            data = actual[name].read_bytes()
            observed = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
            record.update(observed=observed, status="PASS" if observed == entry else "MISMATCH")
        records.append(record)
    strict_pass = sum(r["status"] == "PASS" for r in records)
    return {
        "observed_utc": datetime.now(timezone.utc).isoformat(),
        "delivery_root": str(root),
        "manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        "declared_file_count": manifest["file_count"],
        "manifest_entries": len(expected),
        "actual_files_including_manifest": len(actual),
        "strict_path_and_content_pass": strict_pass,
        "missing_path_content_matches": sum(
            r["status"] == "MISSING_PATH" and bool(r["content_matching_extra_paths"])
            for r in records
        ),
        "extra_paths": extras,
        "case_collisions": [v for v in case_groups.values() if len(v) > 1],
        "status": "PASS" if strict_pass == len(expected) == manifest["file_count"] and not extras else "CONDITIONAL",
        "files": records,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("delivery", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = args.delivery.resolve(strict=True)
    if args.output and args.output.resolve().is_relative_to(root):
        parser.error("Output must be outside the original delivery directory")
    result = verify(root)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "files"}, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
