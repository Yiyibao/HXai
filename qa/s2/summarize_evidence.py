"""Rebuild S2 counters from test logs and the exported synthetic model fixture."""
from pathlib import Path
from collections import Counter
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from datetime import datetime, timezone, timedelta

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "qa/s2"

def instrumentation(name):
    text = (ROOT / ".local" / name).read_text(encoding="utf-8-sig")
    assert "FAILURES!!!" not in text and "Process crashed" not in text
    declared = int(re.search(r"OK \((\d+) tests?\)", text).group(1))
    return {"declared": declared, "passed": len(re.findall(r"INSTRUMENTATION_STATUS_CODE: 0\s", text)),
            "conditional_skips": len(re.findall(r"INSTRUMENTATION_STATUS_CODE: -4\s", text))}

def main():
    result = {"tested_at": datetime.now(timezone(timedelta(hours=8))).isoformat(), "timezone": "Asia/Taipei"}
    result["jvm"] = {}
    for module in ("app", "ai", "search"):
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        for path in (ROOT / "client" / module / "build/test-results/testDebugUnitTest").glob("TEST-*.xml"):
            tree = ET.parse(path).getroot()
            for key in totals:
                totals[key] += int(tree.attrib.get(key, 0))
        assert totals["tests"] and not totals["failures"] and not totals["errors"]
        result["jvm"][module] = totals
    result["android"] = {name: instrumentation(name) for name in (
        "s2-android-final.log", "s2-process-seed.log", "s2-process-verify.log", "s2-delivery-android.log", "s2-delivery-real.log")}
    evidence = json.loads((OUT / "s2-real-memory.json").read_text(encoding="utf-8"))
    state = evidence["state"]
    sources = state["sources"]
    assert len(sources) == 30
    indexes = {source["id"]: i for i, source in enumerate(sources)}
    labels = {source["id"]: i < 20 for i, source in enumerate(sources)}
    items = state["items"]
    # Each fixture has one expected atomic record. Independent semantic review is
    # recorded in the completion report; this script only calculates denominators.
    assert all(len(item["sourceIds"]) == 1 for item in items)
    tp = sum(all(labels[s] for s in item["sourceIds"]) for item in items)
    fp = len(items) - tp
    covered = {s for item in items for s in item["sourceIds"] if labels[s]}
    fn = 20 - len(covered)
    result["evaluation"] = {"cases": 30, "expected_records": 20, "ignore_cases": 10, "tp": tp, "fp": fp, "fn": fn,
                            "precision": tp / (tp + fp), "recall": len(covered) / 20,
                            "statuses": dict(Counter(item.get("status", "CANDIDATE") for item in items)),
                            "method": "Predefined synthetic labels; independent content-to-source review by Codex, not extractor self-scoring; not externally human-reviewed."}
    assert result["evaluation"]["precision"] >= .95 and result["evaluation"]["recall"] >= .85
    rows = []
    for i, source in enumerate(sources):
        records = [item for item in items if source["id"] in item["sourceIds"]]
        rows.append({"case": i + 1, "input": evidence["sources"][i], "expected": "MEMORY" if i < 20 else "IGNORE",
                     "actual": [{"content": item["content"], "type": item["type"], "evidence": item["evidenceType"],
                                 "status": item.get("status", "CANDIDATE")} for item in records]})
    (OUT / "s2-case-results.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    result["apks"] = [{"file": str(path.relative_to(ROOT)).replace("\\", "/"), "bytes": path.stat().st_size,
                       "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                      for path in sorted((ROOT / "client/app/build/outputs/apk/debug").glob("*.apk"))]
    (OUT / "s2-verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
