import hashlib
import asyncio
import json
import os
import re
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse

from api.store import Store, AuthorizationRevoked
from api.office import readiness, register_office

MAX_UPLOAD = 30 * 1024 * 1024
PARSER_VERSION = "4.0.10"
ADAPTER_VERSION = "1"


def create_app(root=None):
    store = Store(Path(root or os.environ.get("MATHDOC_DATA", "/var/lib/mathdoc")))
    app = FastAPI(title="MathStudy document service", docs_url=None, redoc_url=None)
    app.state.store = store

    @app.exception_handler(AuthorizationRevoked)
    async def revoked_after_authentication(_request, _error):
        # Shared by parse and Office uploads; do not leak identity/token details.
        return JSONResponse(status_code=401, content={"detail": "authentication_required"})

    def identity(authorization):
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401, "authentication_required")
        owner = store.authenticate(authorization[7:])
        if not owner:
            raise HTTPException(401, "authentication_required")
        return owner

    def owned(owner, job_id):
        row = store.get(owner, job_id)
        if not row:
            raise HTTPException(404, "job_not_found")
        return row

    def public(row):
        options = json.loads(row["options"])
        return {"taskId": row["id"], "state": row["state"], "errorCode": row["error"],
                "resultRevision": 1 if row["manifest"] else None, "expiresAt": row["expires"],
                "requestedRange": options.get("pages"), "rendererVersion": options.get("rendererVersion"),
                "operation": options.get("operation", "parse")}

    @app.get("/health")
    def health():
        return {"status": "ok", "contractVersion": 1}

    @app.get("/api/v1/capabilities")
    def capabilities(authorization: str | None = Header(None)):
        identity(authorization)
        ready = (store.root / "worker-ready.json").is_file()
        return {"contractVersion": 1, "parser": "MinerU", "parserVersion": PARSER_VERSION,
                "adapterVersion": ADAPTER_VERSION, "tiers": ["basic"] if ready else [],
                "formats": ["pdf", "png", "jpg", "jpeg", "webp"] if ready else [],
                "offline": True, "ready": ready, "maxUploadBytes": MAX_UPLOAD,
                "maxDocumentPages": 200, "maxRequestPages": 5, "maxConcurrentJobs": 1,
                "taskTimeoutSeconds": 900, "retentionSeconds": 86400,
                "officePdf": readiness(store)}

    @app.post("/api/v1/parse-requests/{request_key}/cancel")
    def cancel_parse_request(request_key: str, authorization: str | None = Header(None)):
        owner = identity(authorization)
        if not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", request_key):
            raise HTTPException(422, "invalid_request_key")
        row = store.cancel_request(owner, request_key)
        return public(row) if row else {"taskId": None, "state": "CANCELLED", "operation": "parse"}

    @app.post("/api/v1/jobs", status_code=202)
    async def submit(request: Request, authorization: str | None = Header(None),
                     idempotency_key: str = Header(...), x_source_sha256: str = Header(...),
                     extension: str = "pdf", start_page: int = 1, end_page: int = 1):
        owner = identity(authorization)
        if not (store.root / "worker-ready.json").is_file():
            raise HTTPException(503, "parser_not_ready")
        if not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", idempotency_key) or not re.fullmatch(r"[a-f0-9]{64}", x_source_sha256):
            raise HTTPException(422, "invalid_request")
        if extension not in {"pdf", "png", "jpg", "jpeg", "webp"} or not 1 <= start_page <= end_page <= 200 or end_page - start_page >= 5:
            raise HTTPException(422, "invalid_range_or_format")
        if extension != "pdf" and (start_page != 1 or end_page != 1):
            raise HTTPException(422, "invalid_image_range")
        staged = store.root / "uploads" / str(uuid.uuid4())
        digest, total, prefix = hashlib.sha256(), 0, b""
        try:
            with staged.open("xb") as out:
                stream = request.stream().__aiter__()
                deadline = time.monotonic() + 120
                while True:
                    try:
                        chunk = await asyncio.wait_for(anext(stream), max(0.001, deadline - time.monotonic()))
                    except StopAsyncIteration:
                        break
                    except TimeoutError:
                        raise HTTPException(408, "upload_timeout") from None
                    total += len(chunk)
                    if total > MAX_UPLOAD:
                        raise HTTPException(413, "upload_limit")
                    if len(prefix) < 16:
                        prefix += chunk[:16 - len(prefix)]
                    digest.update(chunk)
                    out.write(chunk)
            signatures = {"pdf": prefix.startswith(b"%PDF-"), "png": prefix.startswith(b"\x89PNG\r\n\x1a\n"),
                          "jpg": prefix.startswith(b"\xff\xd8\xff"), "jpeg": prefix.startswith(b"\xff\xd8\xff"),
                          "webp": prefix.startswith(b"RIFF") and prefix[8:12] == b"WEBP"}
            if not signatures[extension] or digest.hexdigest() != x_source_sha256:
                raise HTTPException(422, "invalid_file_or_digest")
            options = {"sourceSha256": x_source_sha256, "extension": extension,
                       "pages": [start_page, end_page], "tier": "basic", "parserVersion": PARSER_VERSION, "adapterVersion": ADAPTER_VERSION}
            fingerprint = hashlib.sha256(json.dumps(options, sort_keys=True).encode()).hexdigest()
            try:
                return public(store.enqueue(owner, idempotency_key, fingerprint, options, staged))
            except ValueError as exc:
                code = str(exc)
                raise HTTPException(409 if code == "idempotency_conflict" else 429, code) from None
        finally:
            staged.unlink(missing_ok=True)

    @app.get("/api/v1/jobs/{job_id}")
    def status(job_id: str, authorization: str | None = Header(None)):
        return public(owned(identity(authorization), job_id))

    @app.get("/api/v1/jobs/{job_id}/manifest")
    def manifest(job_id: str, authorization: str | None = Header(None)):
        row = owned(identity(authorization), job_id)
        if row["state"] not in {"SUCCEEDED", "PARTIAL"} or row["expires"] <= time.time():
            raise HTTPException(409, "result_unavailable")
        return json.loads(row["manifest"])

    @app.get("/api/v1/jobs/{job_id}/assets/{asset_id}")
    def asset(job_id: str, asset_id: str, authorization: str | None = Header(None)):
        row = owned(identity(authorization), job_id)
        if row["state"] not in {"SUCCEEDED", "PARTIAL"} or row["expires"] <= time.time():
            raise HTTPException(409, "result_unavailable")
        assets = json.loads(row["manifest"])["assets"]
        entry = next((a for a in assets if a["assetId"] == asset_id), None)
        if not entry:
            raise HTTPException(404, "asset_not_found")
        path = store.root / "jobs" / job_id / "published" / entry["path"]
        if not path.is_file() or path.is_symlink():
            raise HTTPException(409, "result_unavailable")
        return FileResponse(path, media_type=entry["mediaType"], filename=asset_id)

    @app.post("/api/v1/jobs/{job_id}/cancel")
    def cancel(job_id: str, authorization: str | None = Header(None)):
        owner = identity(authorization)
        owned(owner, job_id)
        store.cancel(owner, job_id)
        return public(store.get(owner, job_id))

    @app.delete("/api/v1/jobs/{job_id}", status_code=202)
    def delete(job_id: str, authorization: str | None = Header(None)):
        owner = identity(authorization)
        owned(owner, job_id)
        store.cancel(owner, job_id)
        with store.db() as db:
            db.execute("UPDATE jobs SET cancel=1,expires=0 WHERE id=? AND owner=?", (job_id, owner))
        return {"taskId": job_id, "cleanupState": "PENDING"}

    register_office(app, store, identity, public)
    return app


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(create_app(), host="127.0.0.1", port=8766, access_log=False)
