"""Lifecycle contract with controlled Docker transport and a real owned subprocess.

This does not claim Linux container/namespace or fcntl-lock verification.
"""
import importlib
import importlib.util
import subprocess
import sys
import types
import uuid

import pytest
from api.store import Store


@pytest.fixture
def dispatcher(monkeypatch):
    if importlib.util.find_spec('fcntl') is None:
        monkeypatch.setitem(sys.modules, 'fcntl', types.SimpleNamespace(LOCK_EX=2, LOCK_NB=4, flock=lambda *_: None))
    module = importlib.import_module('dispatcher.main')
    # No real mount/unmount or Docker operation occurs in these transport contract tests.
    monkeypatch.setattr(module, 'subprocess', types.SimpleNamespace(
        SubprocessError=subprocess.SubprocessError,
        run=lambda *args, **kwargs: subprocess.CompletedProcess(args[0], 0, '', '')))
    return module


@pytest.fixture
def running_job(tmp_path):
    store = Store(tmp_path); owner, _ = store.issue()
    staged = tmp_path / 'uploads' / str(uuid.uuid4()); staged.write_bytes(b'%PDF-1.4\nSynthetic lifecycle source')
    row = store.enqueue(owner, 'synthetic-lifecycle-request', 'intent', {'extension': 'pdf', 'pages': [1, 1]}, staged)
    store.claim()
    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])
    try:
        yield store, owner, row, child
    finally:
        child.terminate(); child.wait(timeout=15)


def unavailable(*args, **kwargs):
    return subprocess.CompletedProcess(args, 1, '', 'Synthetic Docker transport unavailable')


def test_restart_does_not_free_running_slot_without_stop_confirmation(dispatcher, running_job, monkeypatch):
    store, owner, row, child = running_job
    monkeypatch.setattr(dispatcher, 'ROOT', store.root)
    monkeypatch.setattr(dispatcher, 'docker', unavailable)
    class EndAfterRecovery(RuntimeError):
        pass
    def end(_store):
        raise EndAfterRecovery('end before any new claim')
    monkeypatch.setattr(dispatcher, 'cleanup', end)
    with pytest.raises(RuntimeError):
        dispatcher.main()
    assert child.poll() is None
    assert store.get(owner, row['id'])['state'] == 'RUNNING'
    assert (store.root / 'jobs' / row['id'] / 'input/source.pdf').read_bytes().startswith(b'%PDF-')


def test_cleanup_keeps_source_and_reservation_when_stop_is_unconfirmed(dispatcher, running_job, monkeypatch):
    store, owner, row, child = running_job
    store.cancel(owner, row['id'])
    with store.db() as db:
        db.execute('UPDATE jobs SET expires=0 WHERE id=?', (row['id'],))
    monkeypatch.setattr(dispatcher, 'docker', unavailable)
    try:
        dispatcher.cleanup(store)
    except RuntimeError:
        pass
    assert child.poll() is None
    current = store.get(owner, row['id'])
    assert current['state'] == 'CANCELLED'
    assert current['reserved_bytes'] == row['reserved_bytes'] > 0
    assert (store.root / 'jobs' / row['id'] / 'input/source.pdf').is_file()


def test_missing_containers_allow_normal_restart_only_after_absence_is_confirmed(dispatcher, running_job, monkeypatch):
    store, owner, row, child = running_job
    # The actual old process has terminated; transport can independently report no such task.
    child.terminate(); child.wait(timeout=15)
    monkeypatch.setattr(dispatcher, 'ROOT', store.root)
    def absent(*args, **kwargs):
        if args[:2] == ('container', 'ls'):
            return subprocess.CompletedProcess(args, 0, '', '')
        return subprocess.CompletedProcess(args, 1, '', 'No such container')
    monkeypatch.setattr(dispatcher, 'docker', absent)
    class EndAfterRecovery(RuntimeError):
        pass
    def end(_store):
        raise EndAfterRecovery()
    monkeypatch.setattr(dispatcher, 'cleanup', end)
    with pytest.raises(EndAfterRecovery):
        dispatcher.main()
    current = store.get(owner, row['id'])
    assert current['state'] == 'FAILED' and current['error'] == 'server_restarted'
    assert current['manifest'] is None
    assert (store.root / 'jobs' / row['id'] / 'input/source.pdf').is_file()


def test_daemon_reports_container_still_present_without_stop_ack_cannot_expire(dispatcher, running_job, monkeypatch):
    store, owner, row, child = running_job
    store.cancel(owner, row['id'])
    with store.db() as db:
        db.execute('UPDATE jobs SET expires=0 WHERE id=?', (row['id'],))
    def still_present(*args, **kwargs):
        if args[:2] == ('container', 'ls'):
            return subprocess.CompletedProcess(args, 0, 'mathdoc-' + row['id'] + '\n', '')
        return unavailable(*args, **kwargs)
    monkeypatch.setattr(dispatcher, 'docker', still_present)
    try:
        dispatcher.cleanup(store)
    except RuntimeError:
        pass
    assert child.poll() is None
    assert store.get(owner, row['id'])['state'] == 'CANCELLED'
    assert (store.root / 'jobs' / row['id'] / 'input/source.pdf').is_file()


def test_confirmed_cleanup_releases_files_only_after_the_owned_process_exits(dispatcher, running_job, monkeypatch):
    store, owner, row, child = running_job
    store.cancel(owner, row['id'])
    with store.db() as db:
        db.execute('UPDATE jobs SET expires=0 WHERE id=?', (row['id'],))
    def stopped(*args, **kwargs):
        if args[:2] == ('rm', '-f') and args[2] == 'mathdoc-' + row['id']:
            child.terminate(); child.wait(timeout=15)
        return subprocess.CompletedProcess(args, 0, '', '')
    monkeypatch.setattr(dispatcher, 'docker', stopped)
    dispatcher.cleanup(store)
    assert child.poll() is not None
    assert store.get(owner, row['id'])['state'] == 'EXPIRED'
    assert not (store.root / 'jobs' / row['id']).exists()


def test_execution_error_keeps_running_when_engine_stop_is_unknown(dispatcher, tmp_path, monkeypatch):
    store = Store(tmp_path); owner, _ = store.issue()
    staged = tmp_path / 'uploads' / str(uuid.uuid4()); staged.write_bytes(b'%PDF-1.4 synthetic')
    options = {'extension': 'pdf', 'pages': [1, 1], 'sourceSha256': 'a' * 64,
               'tier': 'basic', 'parserVersion': '4.0.10', 'adapterVersion': '1'}
    row = store.enqueue(owner, 'synthetic-execution-failure', 'intent', options, staged)
    store.claim()
    def failed(*args, **kwargs):
        if args[0] == 'create':
            return subprocess.CompletedProcess(args, 0, '', '')
        if args[0] == 'start':
            raise OSError('Synthetic lost Docker start ACK')
        return unavailable(*args, **kwargs)
    monkeypatch.setattr(dispatcher, 'docker', failed)
    monkeypatch.setattr(dispatcher, 'os', types.SimpleNamespace(chown=lambda *_: None))
    with pytest.raises(dispatcher.WorkerStopUnconfirmed):
        dispatcher.execute(store, row)
    current = store.get(owner, row['id'])
    assert current['state'] == 'RUNNING' and current['manifest'] is None
    assert (store.root / 'jobs' / row['id'] / 'input/source.pdf').is_file()


@pytest.mark.parametrize('action', ['cancel', 'revoke', 'legacy_revoke'])
@pytest.mark.parametrize('boundary', ['after_claim', 'before_start'])
def test_revoked_or_cancelled_claim_cannot_launch_a_new_worker(dispatcher, tmp_path, monkeypatch, action, boundary):
    store = Store(tmp_path); owner, _ = store.issue()
    staged = tmp_path / 'uploads' / str(uuid.uuid4()); staged.write_bytes(b'%PDF-1.4 synthetic')
    options = {'extension': 'pdf', 'pages': [1, 1], 'sourceSha256': 'a' * 64,
               'tier': 'basic', 'parserVersion': '4.0.10', 'adapterVersion': '1'}
    row = store.enqueue(owner, 'synthetic-pre-dispatch-fence', 'intent', options, staged)
    store.claim()
    marker = tmp_path / 'worker-executed'
    child = None
    def restrict():
        if action == 'cancel':
            store.cancel(owner, row['id'])
        elif action == 'revoke':
            store.revoke(owner)
        else:
            with store.db() as db:
                db.execute('UPDATE identities SET active=0 WHERE id=?', (owner,))
    if boundary == 'after_claim':
        restrict()
    def transport(*args, **kwargs):
        nonlocal child
        if args[0] == 'create' and boundary == 'before_start':
            restrict()
        if args[0] == 'start':
            code = 'from pathlib import Path; import time; Path(' + repr(str(marker)) + ').write_text(\"executed\"); time.sleep(120)'
            child = subprocess.Popen([sys.executable, '-c', code])
            import time
            deadline = time.monotonic() + 10
            while not marker.exists():
                assert time.monotonic() < deadline
                time.sleep(0.01)
        if args[:2] == ('rm', '-f') and child is not None and child.poll() is None:
            child.terminate(); child.wait(timeout=15)
        return subprocess.CompletedProcess(args, 0, '', '')
    monkeypatch.setattr(dispatcher, 'docker', transport)
    monkeypatch.setattr(dispatcher, 'os', types.SimpleNamespace(chown=lambda *_: None))
    try:
        dispatcher.execute(store, row)
        assert not marker.exists(), 'Worker actually executed after the claim was cancelled/revoked'
        assert store.get(owner, row['id'])['state'] == 'CANCELLED'
        assert (store.root / 'jobs' / row['id'] / 'input/source.pdf').is_file()
    finally:
        if child is not None and child.poll() is None:
            child.terminate(); child.wait(timeout=15)
