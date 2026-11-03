"""Office rendering uses the existing identity-scoped durable queue, not MinerU."""
import asyncio
import hashlib
import json
import re
import time
import uuid
from fastapi import Header, HTTPException, Request

OFFICE_OPTIONS = {"scope": "print_layout", "includeHiddenSlides": False, "includeNotes": False}


def readiness(store):
    path = store.root / "office-ready.json"
    if not path.is_file() or path.is_symlink():
        return {"ready": False, "formats": [], "renderer": "LibreOffice"}
    value = json.loads(path.read_text())
    return {"ready": True, "formats": ["docx", "xlsx", "pptx"], "renderer": "LibreOffice",
            "rendererVersion": value["rendererVersion"], "options": OFFICE_OPTIONS,
            "maxUploadBytes": 30 * 1024 * 1024, "maxOutputPages": 200, "taskTimeoutSeconds": 300}


def register_office(app, store, identity, public):
    @app.post("/api/v1/conversion-requests/{request_key}/cancel")
    def cancel_request(request_key: str, authorization: str | None = Header(None)):
        owner = identity(authorization)
        if not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", request_key):
            raise HTTPException(422, "invalid_request_key")
        row = store.cancel_request(owner, request_key)
        return public(row) if row else {"taskId": None, "state": "CANCELLED", "operation": "office_pdf"}

    @app.post("/api/v1/conversions", status_code=202)
    async def submit_conversion(request: Request, authorization: str | None = Header(None),
                                idempotency_key: str = Header(...), x_source_sha256: str = Header(...),
                                extension: str = "docx"):
        owner = identity(authorization)
        ready = readiness(store)
        if not ready["ready"]:
            raise HTTPException(503, "office_renderer_not_ready")
        if (extension not in ready["formats"] or not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", idempotency_key)
                or not re.fullmatch(r"[a-f0-9]{64}", x_source_sha256)):
            raise HTTPException(422, "invalid_conversion_request")
        staged = store.root / "uploads" / str(uuid.uuid4())
        digest, total, prefix = hashlib.sha256(), 0, b""
        try:
            with staged.open("xb") as output:
                stream = request.stream().__aiter__(); deadline = time.monotonic() + 120
                while True:
                    try:
                        chunk = await asyncio.wait_for(anext(stream), max(0.001, deadline - time.monotonic()))
                    except StopAsyncIteration:
                        break
                    except TimeoutError:
                        raise HTTPException(408, "upload_timeout") from None
                    total += len(chunk)
                    if total > 30 * 1024 * 1024:
                        raise HTTPException(413, "upload_limit")
                    if len(prefix) < 4:
                        prefix += chunk[:4-len(prefix)]
                    digest.update(chunk); output.write(chunk)
            if prefix != b"PK\x03\x04" or digest.hexdigest() != x_source_sha256:
                raise HTTPException(422, "invalid_file_or_digest")
            options = {"operation": "office_pdf", "sourceSha256": x_source_sha256, "extension": extension,
                       "rendererVersion": ready["rendererVersion"], "renderOptions": OFFICE_OPTIONS}
            fingerprint = hashlib.sha256(json.dumps(options, sort_keys=True).encode()).hexdigest()
            try:
                return public(store.enqueue(owner, idempotency_key, fingerprint, options, staged))
            except ValueError as error:
                raise HTTPException(409 if str(error) == "idempotency_conflict" else 429, str(error)) from None
        finally:
            staged.unlink(missing_ok=True)
