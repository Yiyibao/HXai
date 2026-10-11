"""Export only named current synthetic QA artifacts, never connection settings."""
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / "qa/boundary-fixes-20261008/verified"
ADB = ROOT / ".local/android-sdk/platform-tools/adb.exe"
records = json.loads((QA / "runtime-results.json").read_text(encoding="utf-8-sig"))
start = int(datetime.fromisoformat(records[0]["started"]).timestamp())
def adb(*args):
    return subprocess.run([str(ADB), "-s", "emulator-5554", *args], capture_output=True, check=True).stdout
manifest = []
for remote, name in [("cache/material-real-model.md", "material-real-model.md"),
                     ("cache/material-evidence/fidelity.docx", "word-input.docx"),
                     ("cache/material-evidence/word-vision-output.docx", "word-output.docx")]:
    stamp = int(adb("shell", "run-as", "org.mathstudy.agent.debug", "stat", "-c", "%Y", remote).strip())
    assert stamp >= start, f"Stale fixture: {name}"
    raw = adb("exec-out", "run-as", "org.mathstudy.agent.debug", "cat", remote)
    target = QA / "fixtures" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)
    manifest.append({"name": name, "device_mtime": stamp, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()})
for log in QA.rglob("*.log"):
    for name in set(re.findall(r"review_evidence=(s1-real-(?:teaching|approvals)-[a-f0-9-]{36}\.md)", log.read_text(encoding="utf-8-sig", errors="replace"))):
        remote = "cache/" + name
        stamp = int(adb("shell", "run-as", "org.mathstudy.agent.debug", "stat", "-c", "%Y", remote).strip())
        assert stamp >= start, f"Stale fixture: {name}"
        raw = adb("exec-out", "run-as", "org.mathstudy.agent.debug", "cat", remote)
        (QA / "fixtures" / name).write_bytes(raw)
        manifest.append({"name": name, "device_mtime": stamp, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()})
with ZipFile(QA / "fixtures/word-output.docx") as output, ZipFile(QA / "fixtures/word-input.docx") as source:
    ns = {"m": "http://schemas.openxmlformats.org/officeDocument/2006/math", "w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
    root = ET.fromstring(output.read("word/document.xml"))
    images = lambda zip_: [hashlib.sha256(zip_.read(name)).hexdigest() for name in zip_.namelist() if name.startswith("word/media/")]
    result = {"fractions": [{"numerator": "".join(node.find("m:num", ns).itertext()), "denominator": "".join(node.find("m:den", ns).itertext())} for node in root.findall(".//m:f", ns)],
              "powers": [{"base": "".join(node.find("m:e", ns).itertext()), "exponent": "".join(node.find("m:sup", ns).itertext())} for node in root.findall(".//m:sSup", ns)],
              "tables": len(root.findall(".//w:tbl", ns)), "output_images": images(output), "source_images": images(source)}
    result["original_image_bytes_preserved"] = result["output_images"] == result["source_images"] and len(result["output_images"]) == 1
    assert result["original_image_bytes_preserved"] and result["tables"] >= 1
    (QA / "word-structure.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
(QA / "synthetic-fixture-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
