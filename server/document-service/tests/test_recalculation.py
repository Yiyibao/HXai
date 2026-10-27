import hashlib
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from api.main import create_app


@pytest.fixture
def service(tmp_path):
    app = create_app(tmp_path)
    owner, token = app.state.store.issue()
    other, other_token = app.state.store.issue()
    (tmp_path / 'office-ready.json').write_text(json.dumps({'rendererVersion': 'qa-1', 'recalculationContractVersion': 1}))
    source = Path(__file__).resolve().parents[3] / 'qa/p0-completion-20261009/samples/rich-source.xlsx'
    return TestClient(app), app.state.store, owner, token, other_token, source.read_bytes()


def submit(client, token, payload, key='recalculation_request_0001'):
    return client.post('/api/v1/recalculations', content=payload, headers={
        'Authorization': 'Bearer ' + token, 'Idempotency-Key': key,
        'X-Source-Sha256': hashlib.sha256(payload).hexdigest()})


def test_recalculation_is_scoped_idempotent_and_distinct_from_pdf(service):
    client, store, owner, token, other_token, payload = service
    response = submit(client, token, payload)
    assert response.status_code == 202
    task = response.json()['taskId']
    assert response.json()['operation'] == 'xlsx_recalculate'
    assert submit(client, token, payload).json()['taskId'] == task
    assert submit(client, other_token, payload).json()['taskId'] != task
    headers = {'Authorization': 'Bearer ' + other_token}
    assert client.get('/api/v1/jobs/' + task, headers=headers).status_code == 404
    assert client.get('/api/v1/jobs/' + task + '/manifest', headers=headers).status_code == 404
    pdf = client.post('/api/v1/conversions?extension=xlsx', content=payload, headers={
        'Authorization': 'Bearer ' + token, 'Idempotency-Key': 'recalculation_request_0001',
        'X-Source-Sha256': hashlib.sha256(payload).hexdigest()})
    assert pdf.status_code == 409
    options = json.loads(store.get(owner, task)['options'])
    assert options['extension'] == 'xlsx' and options['renderOptions'] == {'scope': 'recalculate_workbook'}


def test_renderer_upgrade_recovers_original_recalculation_receipt(service):
    client, store, owner, token, _, payload = service
    first = submit(client, token, payload).json()
    (store.root / 'office-ready.json').write_text(json.dumps({'rendererVersion': 'qa-2', 'recalculationContractVersion': 1}))
    recovered = submit(client, token, payload)
    assert recovered.status_code == 202
    assert recovered.json()['taskId'] == first['taskId'] and recovered.json()['rendererVersion'] == 'qa-1'
    changed = payload + b'changed'
    assert submit(client, token, changed).status_code == 409


def test_old_pdf_renderer_does_not_advertise_recalculation(service):
    client, store, _, token, _, payload = service
    (store.root / 'office-ready.json').write_text(json.dumps({'rendererVersion': 'old-pdf-only'}))
    capabilities = client.get('/api/v1/capabilities', headers={'Authorization': 'Bearer ' + token}).json()
    assert capabilities['officePdf']['ready'] is True
    assert capabilities['officeRecalculation']['ready'] is False
    assert submit(client, token, payload).status_code == 503


def test_cancel_before_upload_and_revoked_finish_do_not_revive(service):
    client, store, owner, token, _, payload = service
    headers = {'Authorization': 'Bearer ' + token}
    key = 'recalculate_cancel_before_001'
    response = client.post('/api/v1/recalculation-requests/' + key + '/cancel', headers=headers)
    assert response.status_code == 200 and response.json()['operation'] == 'xlsx_recalculate'
    cancelled = submit(client, token, payload, key).json()
    assert cancelled['state'] == 'CANCELLED'
    job = submit(client, token, payload, 'recalculate_revoked_job_001').json()['taskId']
    assert store.claim()['id'] == job
    store.revoke(owner)
    store.finish(job, 'SUCCEEDED', {'assets': []})
    assert store.get(owner, job)['state'] == 'CANCELLED'
    assert client.get('/api/v1/jobs/' + job, headers=headers).status_code == 401
