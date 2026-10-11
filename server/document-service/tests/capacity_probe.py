"""Synthetic-only live probe. Run on the host as root, never prints credentials."""
import hashlib
import json
import os
import subprocess
import time
import uuid
from pathlib import Path

import httpx

from api.store import Store


def probe(extension, start=1, end=1):
    store = Store(Path("/var/lib/mathdoc"))
    owner, token = store.issue()
    headers = {"Authorization": "Bearer " + token}
    original_ready = (store.root / "worker-ready.json").exists()
    # Local test-only enablement; there is no public route yet.
    (store.root / "worker-ready.json").write_text('{"syntheticProbe":true}')
    source = Path("/tmp/math-scan." + extension).read_bytes()
    report = {"extension": extension, "requestedRange": [start, end], "samples": []}
    job_id = None
    try:
        with httpx.Client(base_url="http://127.0.0.1:8766", timeout=120) as client:
            submitted = client.post("/api/v1/jobs", params={"extension": extension, "start_page": start, "end_page": end}, content=source,
                headers={**headers, "Idempotency-Key": str(uuid.uuid4()), "X-Source-Sha256": hashlib.sha256(source).hexdigest()})
            submitted.raise_for_status()
            job_id = submitted.json()["taskId"]
            began = time.monotonic()
            peak_memory = 0
            peak_cpu = 0
            while time.monotonic() - began < 930:
                status = client.get("/api/v1/jobs/" + job_id, headers=headers).json()
                stat = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}", "mathdoc-" + job_id], capture_output=True, text=True)
                if stat.returncode == 0 and stat.stdout.strip():
                    sample = json.loads(stat.stdout)
                    report["samples"].append({"elapsed": round(time.monotonic() - began, 1), "memory": sample["MemUsage"], "cpu": sample["CPUPerc"]})
                if status["state"] not in {"QUEUED", "RUNNING"}:
                    report["result"] = status
                    break
                time.sleep(1)
            report["elapsedSeconds"] = round(time.monotonic() - began, 2)
            if report.get("result", {}).get("state") in {"SUCCEEDED", "PARTIAL"}:
                manifest = client.get(f"/api/v1/jobs/{job_id}/manifest", headers=headers).json()
                document = client.get(f"/api/v1/jobs/{job_id}/assets/document", headers=headers).json()
                report["manifest"] = manifest
                report["document"] = document
            else:
                report["error"] = "real_parser_failed"
            report["existingServices"] = subprocess.run(["systemctl", "is-active", "mathstudy-api", "yubai-blog"], capture_output=True, text=True).stdout.splitlines()
            client.delete("/api/v1/jobs/" + job_id, headers=headers)
    finally:
        if not original_ready:
            (store.root / "worker-ready.json").unlink(missing_ok=True)
        with store.db() as db:
            db.execute("UPDATE identities SET active=0 WHERE id=?", (owner,))
    Path(f"/opt/mathdoc/capacity-{extension}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({k: v for k, v in report.items() if k not in {"samples", "manifest", "document"}}, ensure_ascii=False))


if __name__ == "__main__":
    import sys
    probe(sys.argv[1] if len(sys.argv) > 1 else "png", int(sys.argv[2]) if len(sys.argv) > 2 else 1, int(sys.argv[3]) if len(sys.argv) > 3 else 1)
