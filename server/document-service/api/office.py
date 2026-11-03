"""Office rendering uses the existing identity-scoped durable queue, not MinerU."""
import asyncio
import hashlib
import json
import re
import time
import uuid
from fastapi import Header, HTTPException, Request
from office_worker.contracts import RECALCULATION_OPTIONS

OFFICE_OPTIONS = {"scope": "print_layout", "includeHiddenSlides": False, "includeNotes": False}


def readiness(store, operation="office_pdf"):
    path = store.root / "office-ready.json"
    if not path.is_file() or path.is_symlink():
        return {"ready": False, "formats": [], "renderer": "LibreOffice"}
    value = json.loads(path.read_text())
    recalculation = operation == "xlsx_recalculate"
    if recalculation and value.get("recalculationContractVersion") != 1:
        return {"ready": False, "formats": [], "renderer": "LibreOffice"}
    return {"ready": True, "formats": ["xlsx"] if recalculation else ["docx", "xlsx", "pptx"], "renderer": "LibreOffice",
            "rendererVersion": value["rendererVersion"], "options": RECALCULATION_OPTIONS if recalculation else OFFICE_OPTIONS,
            "maxUploadBytes": 30 * 1024 * 1024, "maxOutputPages": 200, "taskTimeoutSeconds": 300}


def register_office(app, store, identity, public):
    @app.get("/api/v1/conversion-requests/{request_key}")
    @app.get("/api/v1/recalculation-requests/{request_key}")
    def read_request(request: Request, request_key: str, authorization: str | None = Header(None)):
        owner = identity(authorization)
        if not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", request_key):
            raise HTTPException(422, "invalid_request_key")
        operation = "xlsx_recalculate" if "/recalculation-requests/" in request.url.path else "office_pdf"
        row = store.read_request(owner, request_key, operation)
        if row is None:
            raise HTTPException(404, "request_not_found_or_not_observable")
        options = json.loads(row["options"])
        return {**public(row), "requestKey": row["request_key"],
                "sourceSha256": options.get("sourceSha256"), "sourceExtension": options.get("extension")}

    @app.post("/api/v1/conversion-requests/{request_key}/cancel")
    @app.post("/api/v1/recalculation-requests/{request_key}/cancel")
    def cancel_request(request: Request, request_key: str, authorization: str | None = Header(None)):
        owner = identity(authorization)
        if not re.fullmatch(r"[A-Za-z0-9_-]{16,128}", request_key):
            raise HTTPException(422, "invalid_request_key")
        row = store.cancel_request(owner, request_key)
        operation = "xlsx_recalculate" if "/recalculation-requests/" in request.url.path else "office_pdf"
        return public(row) if row else {"taskId": None, "state": "CANCELLED", "operation": operation}

    async def submit(request, authorization, idempotency_key, x_source_sha256, extension, operation):
        owner = identity(authorization)
        ready = readiness(store, operation)
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
            options = {"operation": operation, "sourceSha256": x_source_sha256, "extension": extension,
                       "rendererVersion": ready["rendererVersion"], "renderOptions": ready["options"]}
            fingerprint = hashlib.sha256(json.dumps(options, sort_keys=True).encode()).hexdigest()
            try:
                return public(store.enqueue(owner, idempotency_key, fingerprint, options, staged))
            except ValueError as error:
                raise HTTPException(409 if str(error) == "idempotency_conflict" else 429, str(error)) from None
        finally:
            staged.unlink(missing_ok=True)

    @app.post("/api/v1/conversions", status_code=202)
    async def submit_conversion(request: Request, authorization: str | None = Header(None),
                                idempotency_key: str = Header(...), x_source_sha256: str = Header(...),
                                extension: str = "docx"):
        return await submit(request, authorization, idempotency_key, x_source_sha256, extension, "office_pdf")

    @app.post("/api/v1/recalculations", status_code=202)
    async def submit_recalculation(request: Request, authorization: str | None = Header(None),
                                  idempotency_key: str = Header(...), x_source_sha256: str = Header(...),
                                  extension: str = "xlsx"):
        return await submit(request, authorization, idempotency_key, x_source_sha256, extension, "xlsx_recalculate")
