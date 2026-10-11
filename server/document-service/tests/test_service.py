import hashlib
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from api.main import create_app
from api.output import validate_output
from worker.run import normalize_pages


@pytest.fixture
def service(tmp_path):
    app = create_app(tmp_path)
    owner, token = app.state.store.issue()
    second, other = app.state.store.issue()
    (tmp_path / "worker-ready.json").write_text("{}")
    return TestClient(app), app.state.store, owner, token, second, other


def submit(client, token, payload=b"%PDF-synthetic", key="test_request_key_001", **options):
    return client.post("/api/v1/jobs", params=options, content=payload,
                       headers={"Authorization": "Bearer " + token, "Idempotency-Key": key,
                                "X-Source-Sha256": hashlib.sha256(payload).hexdigest()})


def test_authentication_and_readiness(service):
    client, store, _, token, _, _ = service
    assert client.get("/api/v1/capabilities").status_code == 401
    assert client.get("/api/v1/capabilities", headers={"Authorization": "Bearer wrong"}).status_code == 401
    (store.root / "worker-ready.json").unlink()
    assert submit(client, token).status_code == 503


@pytest.mark.parametrize("suffix", ["", "/manifest", "/assets/document", "/cancel", "DELETE"])
def test_other_identity_cannot_access(service, suffix):
    client, store, owner, token, _, other = service
    job = submit(client, token).json()["taskId"]
    path = "/api/v1/jobs/" + job
    headers = {"Authorization": "Bearer " + other}
    response = client.delete(path, headers=headers) if suffix == "DELETE" else client.post(path + suffix, headers=headers) if suffix == "/cancel" else client.get(path + suffix, headers=headers)
    assert response.status_code == 404
    assert store.get(owner, job)["state"] == "QUEUED"


def test_idempotency_is_persistent_and_identity_scoped(service):
    client, store, owner, token, _, other = service
    first = submit(client, token).json()["taskId"]
    assert submit(client, token).json()["taskId"] == first
    assert submit(client, token, payload=b"%PDF-different").status_code == 409
    assert submit(client, other).json()["taskId"] != first
    reopened = create_app(store.root).state.store
    assert reopened.get(owner, first)["state"] == "QUEUED"
    assert reopened.authenticate(token) == owner


def test_queue_limit_and_cancel_race(service):
    client, store, owner, token, _, _ = service
    job = submit(client, token).json()["taskId"]
    for i in range(2):
        assert submit(client, token, key=f"test_request_key_{i+2:03}").status_code == 202
    assert submit(client, token, key="test_request_key_004").status_code == 429
    assert store.claim()["id"] == job
    headers = {"Authorization": "Bearer " + token}
    assert client.post(f"/api/v1/jobs/{job}/cancel", headers=headers).json()["state"] == "CANCELLED"
    store.finish(job, "SUCCEEDED", {"assets": []})
    assert store.get(owner, job)["state"] == "CANCELLED"
    assert store.get(owner, job)["manifest"] is None
    assert client.post(f"/api/v1/jobs/{job}/cancel", headers=headers).json()["state"] == "CANCELLED"


def test_parse_request_cancel_stops_lost_ack_without_upload(service):
    client, store, owner, token, _, _ = service
    key = "parse_lost_ack_0001"
    job = submit(client, token, key=key).json()["taskId"]
    assert store.claim()["id"] == job
    headers = {"Authorization": "Bearer " + token}
    response = client.post(f"/api/v1/parse-requests/{key}/cancel", headers=headers)
    assert response.status_code == 200
    assert response.json()["taskId"] == job
    assert response.json()["state"] == "CANCELLED"
    store.finish(job, "SUCCEEDED", {"assets": []})
    assert store.get(owner, job)["state"] == "CANCELLED"
    assert store.get(owner, job)["manifest"] is None


def test_parse_request_cancel_persists_before_upload_and_restart(service):
    client, store, owner, token, second, other = service
    key = "parse_offline_cancel_001"
    path = f"/api/v1/parse-requests/{key}/cancel"
    headers = {"Authorization": "Bearer " + token}
    assert client.post(path).status_code == 401
    assert client.post("/api/v1/parse-requests/bad/cancel", headers=headers).status_code == 422
    assert client.post(path, headers=headers).json() == {"taskId": None, "state": "CANCELLED", "operation": "parse"}
    reopened = TestClient(create_app(store.root))
    result = submit(reopened, token, key=key).json()
    assert result["state"] == "CANCELLED"
    assert not (store.root / "jobs" / result["taskId"]).exists()
    assert reopened.post(path, headers=headers).json()["taskId"] == result["taskId"]
    other_job = submit(reopened, other, key=key).json()["taskId"]
    assert store.get(second, other_job)["state"] == "QUEUED"


@pytest.mark.parametrize("options", [{"start_page": 0}, {"end_page": 6}, {"extension": "docx"}, {"extension": "png", "end_page": 2}])
def test_invalid_options(service, options):
    client, _, _, token, _, _ = service
    assert submit(client, token, **options).status_code == 422


def test_signature_and_hash_validation(service):
    client, _, _, token, _, _ = service
    assert submit(client, token, payload=b"not a PDF").status_code == 422
    response = client.post("/api/v1/jobs", content=b"%PDF-synthetic", headers={"Authorization": "Bearer " + token,
                           "Idempotency-Key": "test_request_key_001", "X-Source-Sha256": "0" * 64})
    assert response.status_code == 422


def make_output(directory):
    output = directory / "output"
    output.mkdir()
    manifest = {"contractVersion": 1, "sourceSha256": "a" * 64, "tier": "basic", "parserVersion": "4.0.10",
                "requestedRange": [2, 2], "processedPages": [2], "failedPages": [], "totalPages": 3, "complete": False,
                "assets": [{"assetId": "document", "path": "document.json", "mediaType": "application/json"}]}
    document = {k: v for k, v in manifest.items() if k != "assets"}
    document["blocks"] = [{"page": 2, "text": "test"}]
    (output / "document.json").write_text(json.dumps(document))
    (output / "manifest.json").write_text(json.dumps(manifest))
    return manifest, {"sourceSha256": "a" * 64, "pages": [2, 2]}


def test_atomic_publish_and_hash(tmp_path):
    _, options = make_output(tmp_path)
    result = validate_output(tmp_path, options)
    asset = result["assets"][0]
    assert hashlib.sha256((tmp_path / "published" / asset["path"]).read_bytes()).hexdigest() == asset["sha256"]
    assert not (tmp_path / "publishing").exists()


@pytest.mark.parametrize("mutation", ["traversal", "coverage", "complete", "source", "missing"])
def test_untrusted_output_rejected(tmp_path, mutation):
    manifest, options = make_output(tmp_path)
    if mutation == "traversal":
        manifest["assets"][0]["path"] = "../secret"
    elif mutation == "coverage":
        manifest["processedPages"] = [1]
    elif mutation == "complete":
        manifest["complete"] = True
    elif mutation == "source":
        manifest["sourceSha256"] = "b" * 64
    else:
        (tmp_path / "output/document.json").unlink()
    (tmp_path / "output/manifest.json").write_text(json.dumps(manifest))
    with pytest.raises((ValueError, FileNotFoundError)):
        validate_output(tmp_path, options)
    assert not (tmp_path / "published").exists()


def test_page_mapping_blank_pages_formula_and_html():
    blocks, pages, blank = normalize_pages([
        {"page_idx": 1, "blocks": [{"type": "equation", "content": "x^2"},
                                  {"type": "table_body", "content": '<table><tr><td>A</td><td>B<script>secret()</script></td></tr></table>'}]},
        {"page_idx": 2, "blocks": []}], [2, 3])
    assert pages == [2, 3] and blank == [3]
    assert blocks[0]["latex"] == "x^2" and blocks[0]["page"] == 2
    assert "secret" not in blocks[1]["text"] and "A\tB" in blocks[1]["text"]
    with pytest.raises(ValueError):
        normalize_pages([{"page_idx": 0, "blocks": []}], [2])
