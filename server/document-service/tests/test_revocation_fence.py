"""Synthetic, local-only revocation/queue regression tests. No Docker or model API."""
import asyncio
import hashlib
import json
import sqlite3
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
from api.main import create_app
from api.store import AuthorizationRevoked, Store


def stage(store, data=b'%PDF-1.4\nSYNTHETIC\n'):
    path = store.root / 'uploads' / str(uuid.uuid4())
    path.write_bytes(data)
    return path


def enqueue(store, owner, key='test-request-key-00001'):
    return store.enqueue(owner, key, 'fingerprint', {'extension': 'pdf', 'pages': [1, 1]}, stage(store))


def test_active_idempotent_receipt_is_preserved(tmp_path):
    store = Store(tmp_path)
    owner, _ = store.issue()
    first = enqueue(store, owner)
    second = enqueue(store, owner)
    assert second['id'] == first['id']
    with pytest.raises(ValueError, match='idempotency_conflict'):
        store.enqueue(owner, 'test-request-key-00001', 'different', {'extension': 'pdf'}, stage(store))


def test_revoke_cancels_running_and_queued_without_deleting_materials(tmp_path):
    store = Store(tmp_path)
    owner, token = store.issue()
    other, other_token = store.issue()
    first = enqueue(store, owner, 'first-request-key')
    queued = enqueue(store, owner, 'second-request-key')
    preserved = enqueue(store, other, 'other-request-key')
    assert store.claim()['id'] == first['id']
    before = store.get(owner, first['id'])['reserved_bytes']
    assert store.revoke(owner)
    assert store.authenticate(token) is None
    assert store.authenticate(other_token) == other
    for job in (first, queued):
        row = store.get(owner, job['id'])
        assert row['state'] == 'CANCELLED' and row['cancel'] == 1
        assert row['error'] == 'authorization_revoked'
        assert (store.root / 'jobs' / job['id'] / 'input' / 'source.pdf').is_file()
    assert store.get(owner, first['id'])['reserved_bytes'] == before
    assert store.get(other, preserved['id'])['state'] == 'QUEUED'
    assert store.claim()['id'] == preserved['id']


def test_old_receipt_cannot_be_returned_after_revocation(tmp_path):
    store = Store(tmp_path)
    owner, _ = store.issue()
    enqueue(store, owner)
    store.revoke(owner)
    with pytest.raises(AuthorizationRevoked):
        enqueue(store, owner)


def test_late_finish_does_not_resurrect_revoked_task(tmp_path):
    store = Store(tmp_path)
    owner, _ = store.issue()
    job = enqueue(store, owner)
    store.claim()
    store.revoke(owner)
    store.finish(job['id'], 'SUCCEEDED', {'assets': [{'private': 'synthetic'}]})
    row = store.get(owner, job['id'])
    assert row['state'] == 'CANCELLED' and row['manifest'] is None


def test_legacy_raw_sql_revoke_is_also_fenced_at_finish(tmp_path):
    store = Store(tmp_path)
    owner, _ = store.issue()
    job = enqueue(store, owner)
    store.claim()
    with store.db() as db:
        db.execute('UPDATE identities SET active=0 WHERE id=?', (owner,))
    store.finish(job['id'], 'SUCCEEDED', {'assets': []})
    assert store.get(owner, job['id'])['state'] == 'CANCELLED'
    assert store.get(owner, job['id'])['manifest'] is None


def test_cancel_receipt_remains_idempotent_for_active_identity(tmp_path):
    store = Store(tmp_path)
    owner, _ = store.issue()
    assert store.cancel_request(owner, 'test-request-key-00001') is None
    first = enqueue(store, owner)
    second = enqueue(store, owner)
    assert first['state'] == second['state'] == 'CANCELLED'
    assert first['id'] == second['id']
    assert store.claim() is None


def test_owner_isolation_and_revoke_idempotency(tmp_path):
    store = Store(tmp_path)
    first, _ = store.issue()
    second, _ = store.issue()
    job = enqueue(store, first)
    assert store.get(second, job['id']) is None
    assert store.revoke('missing') is False
    assert store.revoke(first) and store.revoke(first)
    assert store.get(first, job['id'])['state'] == 'CANCELLED'


def test_queue_limit_is_not_removed_by_cost_budget_decision(tmp_path):
    store = Store(tmp_path)
    owner, _ = store.issue()
    for i in range(3):
        enqueue(store, owner, f'queue-request-{i}')
    with pytest.raises(ValueError, match='queue_full'):
        enqueue(store, owner, 'queue-request-4')


@pytest.mark.parametrize('operation', ['parse', 'office'])
@pytest.mark.parametrize('revoke_during_upload', [False, True])
def test_asgi_upload_rechecks_authorization(tmp_path, operation, revoke_during_upload):
    app = create_app(tmp_path)
    store = app.state.store
    owner, token = store.issue()
    (tmp_path / 'worker-ready.json').write_text('{}')
    (tmp_path / 'office-ready.json').write_text(json.dumps({'rendererVersion': 'synthetic-renderer'}))
    payload = b'%PDF-1.4\nSYNTHETIC\n' if operation == 'parse' else b'PK\x03\x04SYNTHETIC-PACKAGE-HEADER'
    endpoint = '/api/v1/jobs?extension=pdf' if operation == 'parse' else '/api/v1/conversions?extension=docx'

    async def chunks():
        yield payload[:8]
        if revoke_during_upload:
            store.revoke(owner)
        yield payload[8:]

    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test.invalid') as client:
            return await client.post(endpoint, content=chunks(), headers={
                'Authorization': 'Bearer ' + token,
                'Idempotency-Key': 'synthetic-upload-request-0001',
                'X-Source-Sha256': hashlib.sha256(payload).hexdigest(),
            })

    result = asyncio.run(run())
    assert list((tmp_path / 'uploads').iterdir()) == []
    if revoke_during_upload:
        assert result.status_code == 401, result.text
        assert result.json() == {'detail': 'authentication_required'}
        assert token not in result.text and owner not in result.text
        with store.db() as db:
            assert db.execute('SELECT COUNT(*) FROM jobs').fetchone()[0] == 0
    else:
        assert result.status_code == 202, result.text
        row = store.get(owner, result.json()['taskId'])
        assert row['state'] == 'QUEUED'
        assert row['options'] and row['reserved_bytes'] > len(payload)


def test_cross_owner_http_status_is_404(tmp_path):
    app = create_app(tmp_path)
    store = app.state.store
    owner, _ = store.issue()
    _, other_token = store.issue()
    job = enqueue(store, owner)
    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test.invalid') as client:
            return await client.get('/api/v1/jobs/' + job['id'], headers={'Authorization': 'Bearer ' + other_token})
    assert asyncio.run(run()).status_code == 404


def test_revoke_claim_race_is_linearizable(tmp_path):
    store = Store(tmp_path)
    for i in range(20):
        owner, _ = store.issue()
        job = enqueue(store, owner, f'race-{i}')
        barrier = threading.Barrier(2)
        def claim():
            barrier.wait()
            return store.claim()
        def revoke():
            barrier.wait()
            return store.revoke(owner)
        with ThreadPoolExecutor(max_workers=2) as pool:
            a, b = pool.submit(claim), pool.submit(revoke)
            claimed = a.result()
            assert b.result()
        # Either claim linearized first, or it saw the revoke. Neither may publish.
        assert claimed is None or claimed['id'] == job['id']
        store.finish(job['id'], 'SUCCEEDED', {'assets': []})
        assert store.get(owner, job['id'])['state'] == 'CANCELLED'
        assert store.get(owner, job['id'])['manifest'] is None


def test_reopening_database_preserves_state_and_source(tmp_path):
    store = Store(tmp_path)
    owner, _ = store.issue()
    job = enqueue(store, owner)
    source = store.root / 'jobs' / job['id'] / 'input' / 'source.pdf'
    expected = source.read_bytes()
    store.revoke(owner)
    reopened = Store(tmp_path)
    assert reopened.get(owner, job['id'])['state'] == 'CANCELLED'
    assert source.read_bytes() == expected
    assert reopened.claim() is None
