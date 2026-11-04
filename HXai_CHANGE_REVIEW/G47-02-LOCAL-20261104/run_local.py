"""Run synthetic local quality and cloud protocol checks. No paid model or production host."""
import json, os, shutil, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent / "local_results.json"
run_root = Path(os.environ["HXAI_RUN_DIR"])
results = []

def run(name, cmd):
    print("CMD", " ".join(cmd), flush=True)
    completed = subprocess.run(cmd, cwd=ROOT, text=True, capture_output=True)
    tail = ((completed.stdout or "") + (completed.stderr or ""))[-1500:]
    print(tail, flush=True)
    results.append({"name": name, "exit": completed.returncode, "tail": tail})
    return completed.returncode

run("quality-prepare", [sys.executable, "-m", "unittest", "scripts/tests/test_prepare_r27_live_quality.py"])
run("quality-import", [sys.executable, "-m", "unittest", "scripts/tests/test_import_r27_quality_answers.py"])
for script in (
    "verify_r27_authority.py",
    "verify_r27_cloud.py",
    "verify_r29_tls.py",
    "verify_r29_validation.py",
    "verify_r29_documents.py",
):
    folder = run_root / script.replace(".py", "")
    run(script, [sys.executable, str(ROOT / "scripts" / script), "--run-dir", str(folder)])
    result = folder / "RESULT.json"
    if result.exists():
        shutil.copyfile(result, Path(__file__).resolve().parent / (script + ".result.json"))
OUT.write_text(json.dumps(results, indent=2), encoding="utf-8")
raise SystemExit(0 if all(item["exit"] == 0 for item in results) else 1)
