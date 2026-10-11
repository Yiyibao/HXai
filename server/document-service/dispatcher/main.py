import fcntl
import hashlib
import json
import os
import shutil
import subprocess
import time
import uuid
from pathlib import Path, PurePosixPath

from api.store import Store
from api.output import validate_output
from api.office import OFFICE_OPTIONS
from api.office_output import validate_office_output

ROOT = Path(os.environ.get("MATHDOC_DATA", "/var/lib/mathdoc"))
MODELS = Path("/var/lib/mathdoc-models")
IMAGE = os.environ.get("MATHDOC_WORKER_IMAGE", "mathdoc-worker:4.0.10-1")
OFFICE_IMAGE = os.environ.get("MATHDOC_OFFICE_IMAGE", "")


def docker(*args, check=True):
    return subprocess.run(["/usr/bin/docker", *args], capture_output=True, text=True, timeout=30, check=check)


def safe_id(value):
    try:
        return str(uuid.UUID(value)) == value
    except (ValueError, TypeError):
        return False



def cleanup(store):
    store.cleanup_orphan_jobs()
    now = time.time()
    with store.db() as db:
        rows = db.execute("SELECT id FROM jobs WHERE expires<=? AND state!='EXPIRED'", (now,)).fetchall()
    for row in rows:
        if not safe_id(row["id"]):
            raise RuntimeError("invalid_cleanup_identity")
        docker("rm", "-f", "mathdoc-" + row["id"], check=False)
        directory = store.root / "jobs" / row["id"]
        if directory.parent != store.root / "jobs" or directory.resolve() != directory or directory.is_symlink():
            raise RuntimeError("invalid_cleanup_path")
        shutil.rmtree(directory, ignore_errors=False) if directory.exists() else None
        with store.db() as db:
            db.execute("UPDATE jobs SET state='EXPIRED',manifest=NULL WHERE id=?", (row["id"],))
    for path in (store.root / "uploads").iterdir():
        if path.is_file() and now - path.stat().st_mtime > 3600:
            path.unlink()
    # Keep brief deletion receipts; remove source hashes and request fingerprints
    # after seven days instead of accumulating a permanent material catalogue.
    with store.db() as db:
        db.execute("DELETE FROM jobs WHERE state='EXPIRED' AND updated<?", (now - 7 * 86400,))
        db.execute("DELETE FROM request_cancellations WHERE created<?", (now - 7 * 86400,))


def execute(store, row):
    job_id, options = row["id"], json.loads(row["options"])
    directory = store.root / "jobs" / job_id
    name = "mathdoc-" + job_id
    # Quota-bound tmpfs: task output cannot fill the host disk.
    mounted = False
    validated = False
    office = options.get("operation") == "office_pdf"
    try:
        if str(uuid.UUID(job_id)) != job_id or directory.is_symlink() or directory.resolve() != directory:
            raise ValueError("invalid_task_directory")
        if office:
            allowed = {"operation", "sourceSha256", "extension", "rendererVersion", "renderOptions"}
            if (set(options) != allowed or options["extension"] not in {"docx", "xlsx", "pptx"}
                    or options["renderOptions"] != OFFICE_OPTIONS or not OFFICE_IMAGE.startswith("sha256:")
                    or len(OFFICE_IMAGE) != 71):
                raise ValueError("invalid_conversion_options")
        else:
            allowed = {"sourceSha256", "extension", "pages", "tier", "parserVersion", "adapterVersion"}
            if set(options) != allowed or options["extension"] not in {"pdf", "png", "jpg", "jpeg", "webp"} or options["tier"] != "basic":
                raise ValueError("invalid_task_options")
            start, end = options["pages"]
            if type(start) is not int or type(end) is not int or not 1 <= start <= end <= 200 or end - start >= 5:
                raise ValueError("invalid_task_range")
        for path in (directory / "input", directory / "output"):
            if path.is_symlink() or path.resolve() != path:
                raise ValueError("invalid_task_directory")
        source = directory / "input" / ("source." + options["extension"])
        request_file = directory / "input" / "request.json"
        if source.is_symlink() or request_file.is_symlink() or source.stat().st_size > 30 * 1024 * 1024 or request_file.stat().st_size > 4096:
            raise ValueError("invalid_task_input")
        if json.loads(request_file.read_text()) != options:
            raise ValueError("invalid_task_input")
        validated = True
        subprocess.run(["mount", "-t", "tmpfs", "-o", "size=192M,mode=0700", "tmpfs", str(directory / "output")], check=True)
        mounted = True
        uid = (store.root / "tasks.sqlite3").stat().st_uid
        os.chown(directory / "output", uid, uid)
        extra = [] if office else ["--tmpfs", "/models/.locks:rw,noexec,nosuid,size=1m,mode=1777", "--mount", f"type=bind,src={MODELS},dst=/models,readonly"]
        image = OFFICE_IMAGE if office else IMAGE
        docker("create", "--name", name, "--network", "none", "--user", f"{uid}:{uid}",
               "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--pids-limit", "192",
               "--memory", "4g", "--memory-swap", "4g", "--cpus", "4", "--ulimit", "nofile=256:256",
               "--log-driver", "none", "--tmpfs", "/tmp:rw,noexec,nosuid,size=384m,mode=1777",
               "--mount", f"type=bind,src={directory / 'input'},dst=/input,readonly",
               "--mount", f"type=bind,src={directory / 'output'},dst=/output",
               *extra, image)
        docker("start", name)
        started = time.monotonic()
        while True:
            current = store.get(row["owner"], job_id)
            if current["cancel"]:
                docker("rm", "-f", name, check=False)
                return
            if time.monotonic() - started > (300 if office else 900):
                docker("rm", "-f", name, check=False)
                store.finish(job_id, "FAILED", error="task_timeout")
                return
            state = json.loads(docker("inspect", "--format", "{{json .State}}", name).stdout)
            if not state["Running"]:
                break
            time.sleep(1)
        if state["ExitCode"] != 0:
            code = "memory_limit" if state.get("OOMKilled") else "office_conversion_failed" if office else "parse_failed"
            error_file = directory / "output" / "error.json"
            if not state.get("OOMKilled") and error_file.is_file() and not error_file.is_symlink() and error_file.stat().st_size < 1024:
                candidate = json.loads(error_file.read_text()).get("errorCode")
                if candidate in {"encrypted_pdf", "page_limit", "image_limit", "output_limit", "text_limit", "invalid_page_mapping",
                                  "invalid_office_input", "source_digest_mismatch", "invalid_office_package", "encrypted_or_unsafe_office",
                                  "active_or_external_office_content", "office_expansion_limit", "office_xml_limit", "unsafe_office_xml",
                                  "external_office_resource", "renderer_version_changed", "office_load_failed", "office_start_failed",
                                  "office_formula_limit", "formula_recalculation_failed", "conversion_page_limit"}:
                    code = candidate
            store.finish(job_id, "FAILED", error=code)
            return
        # The fresh validator has no source file, models, credentials or host socket.
        validation = docker("run", "--rm", "--name", name + "-validate", "--network", "none", "--read-only",
                            "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--user", f"{uid}:{uid}",
                            "--pids-limit", "64", "--memory", "768m", "--memory-swap", "768m", "--cpus", "1",
                            "--log-driver", "none", "--mount", f"type=bind,src={directory / 'output'},dst=/output,readonly",
                            "--entrypoint", "/usr/bin/python3" if office else "python", image, "-m", "office_worker.check_output" if office else "worker.check_output", check=False)
        if validation.returncode:
            raise ValueError("invalid_output")
        manifest = validate_office_output(directory, options) if office else validate_output(directory, options)
        store.finish(job_id, "PARTIAL" if not office and manifest["failedPages"] else "SUCCEEDED", manifest)
    except Exception:
        store.finish(job_id, "FAILED", error="worker_or_output_failure")
    finally:
        if validated:
            docker("rm", "-f", name + "-validate", check=False)
            docker("rm", "-f", name, check=False)
            if mounted:
                subprocess.run(["umount", str(directory / "output")], check=True)
            # Cancellation may win while publishing. Discard all uncommitted output.
            current = store.get(row["owner"], job_id)
            if current["state"] not in {"SUCCEEDED", "PARTIAL"}:
                shutil.rmtree(directory / "published", ignore_errors=True)
            shutil.rmtree(directory / "publishing", ignore_errors=True)


def main():
    store = Store(ROOT)
    with (ROOT / "dispatcher.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        # Stop old containers before declaring interrupted tasks failed.
        with store.db() as db:
            running = db.execute("SELECT id FROM jobs WHERE state='RUNNING'").fetchall()
        for row in running:
            if not safe_id(row["id"]):
                continue
            docker("rm", "-f", "mathdoc-" + row["id"], check=False)
            subprocess.run(["umount", str(ROOT / "jobs" / row["id"] / "output")], capture_output=True)
            store.finish(row["id"], "FAILED", error="server_restarted")
        # Cancellation can have won just before a dispatcher crash. Stop every
        # known task container, including terminal tasks, before accepting work.
        with store.db() as db:
            known = db.execute("SELECT id FROM jobs").fetchall()
        for row in known:
            if not safe_id(row["id"]):
                continue
            docker("rm", "-f", "mathdoc-" + row["id"] + "-validate", check=False)
            docker("rm", "-f", "mathdoc-" + row["id"], check=False)
            subprocess.run(["umount", str(ROOT / "jobs" / row["id"] / "output")], capture_output=True)
        while True:
            cleanup(store)
            job = store.claim()
            if job:
                execute(store, job)
            else:
                time.sleep(2)


if __name__ == "__main__":
    main()
