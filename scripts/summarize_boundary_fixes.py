"""Summarize the final case execution; retain failed earlier executions separately."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / "qa/boundary-fixes-20261008"
VERIFIED = QA / "verified"
executions = json.loads((VERIFIED / "runtime-results.json").read_text(encoding="utf-8-sig"))
if isinstance(executions, dict):
    executions = [executions]
latest = {entry["name"]: entry for entry in executions}
required = {"ConversationBoundaryFixTest", "WordFormulaAssertionTest", "StudyChatCompletionTest", "ChatNetworkFaultTest",
            "ProjectReviewAuditTest", "MaterialFilesTest", "MaterialFidelityTest", "LearningMemoryPersistenceTest",
            "BackupReliabilityTest", "MaterialDeliveryTest", "ProjectReviewDeliveryTest", "MaterialPageTest",
            "runtime-seed", "runtime-verify", "real-teaching-approval", "real-material"}
if not required.issubset(latest):
    raise RuntimeError("Review is still running; no final summary written")
cases = []
for entry in latest.values():
    text = (VERIFIED / entry["log"]).read_text(encoding="utf-8-sig")
    terminal = {}
    for name, code in re.findall(r"INSTRUMENTATION_STATUS: test=([^\r\n]+)\r?\nINSTRUMENTATION_STATUS_CODE: (-?\d+)", text):
        if int(code) != 1:
            terminal[name] = int(code)
    for name, code in terminal.items():
        cases.append({"execution": entry["name"], "test": name, "status": {0: "PASS", -3: "SKIP", -4: "SKIP", -2: "FAIL", -1: "ERROR"}.get(code, f"UNKNOWN_{code}"), "log": entry["log"]})
final_log = QA / "final-boundary-network-verified.log"
fields, overrides = {}, []
for line in final_log.read_text(encoding="utf-8-sig").splitlines():
    if line.startswith("INSTRUMENTATION_STATUS: "):
        key, _, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
        if key in {"class", "test"}:
            fields[key] = value
    elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
        code = int(line.partition(":")[2])
        if code != 1 and "class" in fields and "test" in fields:
            overrides.append({"execution": fields["class"].split(".")[-1], "test": fields["test"],
                              "status": {0: "PASS", -3: "SKIP", -4: "SKIP", -2: "FAIL", -1: "ERROR"}.get(code, f"UNKNOWN_{code}"),
                              "log": "../final-boundary-network-verified.log"})
        fields = {}
if len(overrides) != 26 or "OK (26 tests)" not in final_log.read_text(encoding="utf-8-sig"):
    raise RuntimeError("Final joint verification is incomplete or failed")
by_case = {(case["execution"], case["test"]): case for case in cases}
by_case.update({(case["execution"], case["test"]): case for case in overrides})
cases = list(by_case.values())
counts = {status: sum(case["status"] == status for case in cases) for status in ("PASS", "FAIL", "ERROR", "SKIP")}
unit = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
for path in (VERIFIED / "jvm-app-final").glob("TEST-*.xml"):
    attrs = ET.parse(path).getroot().attrib
    for key in unit:
        unit[key] += int(attrs.get(key, 0))
lint = ET.parse(QA / "lint-results-verified.xml").getroot()
lint_counts = {severity: sum(node.get("severity") == severity for node in lint.findall("issue")) for severity in ("Error", "Fatal", "Warning", "Information")}
server_text = (QA / "server-tests-final.log").read_text(encoding="utf-8-sig")
summary = {"complete": True, "scope": "Six fixes plus selected core regressions; not a new exhaustive device/provider audit", "app_jvm": unit,
           "server_tests": int(re.search(r"(\d+) passed", server_text)[1]),
           "server_warning": int(re.search(r"(\d+) warning", server_text)[1]), "android_terminal": counts, "lint": lint_counts,
           "executions": executions, "final_joint_override": "final-boundary-network-verified.log",
           "version_boundary": "Full core and real tests used verified/runtime-apk-hashes.json. Final APK adds title FTS consistency; final joint run covers data/index/Word assertions/network. See final-apk-hashes.json.",
           "cases": cases, "first_boundary_failure_retained": "ConversationBoundaryFixTest-first-failure.log"}
(QA / "test-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
manifest = []
for path in sorted(QA.rglob("*")):
    if not path.is_file() or any(part.startswith("pytest-") for part in path.relative_to(QA).parts) or path.name == "evidence-manifest.json":
        continue
    manifest.append({"file": path.relative_to(QA).as_posix(), "bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
(QA / "evidence-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"app_jvm": unit, "android_terminal": counts, "lint": lint_counts}, indent=2))
