import subprocess
import sys
from types import SimpleNamespace
import pytest
from office_worker import engine


class Disposed(Exception):
    pass


class BodyFailure(Exception):
    pass


@pytest.fixture
def runtime(monkeypatch):
    def create(close_error=None, terminate_error=None, exit_code=0, timeout=False):
        state = SimpleNamespace(alive=True, killed=False, waits=0, document_closed=False)

        class Process:
            pid = 43123

            def poll(self):
                return None if state.alive else exit_code

            def wait(self, timeout=None):
                state.waits += 1
                if timeout is not None and state.waits == 1 and state.timeout:
                    raise subprocess.TimeoutExpired('owned-office', timeout)
                state.alive = False
                return -9 if state.killed else exit_code

            def kill(self):
                state.killed = True

        class Document:
            def supportsService(self, service):
                return True

            def close(self, ownership):
                state.document_closed = True
                if close_error:
                    raise close_error

        class Desktop:
            def loadComponentFromURL(self, *args):
                return Document()

            def terminate(self):
                if terminate_error:
                    raise terminate_error

        desktop = Desktop()
        context = SimpleNamespace()
        resolver = SimpleNamespace(resolve=lambda _: context)
        context.ServiceManager = SimpleNamespace(createInstanceWithContext=lambda name, _: resolver if 'Resolver' in name else desktop)
        state.timeout = timeout
        monkeypatch.setitem(sys.modules, 'uno', SimpleNamespace(getComponentContext=lambda: context))
        monkeypatch.setitem(sys.modules, 'com.sun.star.document.MacroExecMode', SimpleNamespace(NEVER_EXECUTE=4))
        monkeypatch.setitem(sys.modules, 'com.sun.star.document.UpdateDocMode', SimpleNamespace(NO_UPDATE=0))
        monkeypatch.setitem(sys.modules, 'com.sun.star.lang', SimpleNamespace(DisposedException=Disposed))
        monkeypatch.setattr(engine, 'property_value', lambda key, value: (key, value))
        monkeypatch.setattr(engine.subprocess, 'Popen', lambda *args, **kwargs: Process())
        def owned_taskkill(*args, **kwargs):
            state.killed = True
            return subprocess.CompletedProcess(args, 0)
        monkeypatch.setattr(engine.subprocess, 'run', owned_taskkill)
        return state
    return create


def test_clean_owned_exit_reconciles_lost_close_ack_without_replaying_body(runtime, tmp_path):
    state = runtime(close_error=Disposed('close ACK lost'), terminate_error=Disposed('already gone'))
    calls = []
    with engine.office_document(tmp_path / 'source.docx', 'docx'):
        calls.append('export-once')
    assert calls == ['export-once'] and state.document_closed and not state.alive and not state.killed


@pytest.mark.parametrize('exit_code', [1, 77])
def test_nonzero_exit_cannot_be_accepted_even_when_operation_returned(runtime, tmp_path, exit_code):
    state = runtime(exit_code=exit_code)
    with pytest.raises(ValueError, match='office_shutdown_failed'):
        with engine.office_document(tmp_path / 'source.docx', 'docx'):
            pass
    assert not state.alive


def test_disposed_bridge_is_not_success_when_server_requires_forced_stop(runtime, tmp_path):
    state = runtime(close_error=Disposed('bridge lost'), timeout=True)
    with pytest.raises(ValueError, match='office_shutdown_failed'):
        with engine.office_document(tmp_path / 'source.docx', 'docx'):
            pass
    assert state.killed and not state.alive


def test_body_failure_is_not_overwritten_by_late_cleanup_ack_failure(runtime, tmp_path):
    state = runtime(close_error=Disposed('late close'), terminate_error=Disposed('late terminate'))
    with pytest.raises(BodyFailure, match='actual export failure'):
        with engine.office_document(tmp_path / 'source.docx', 'docx'):
            raise BodyFailure('actual export failure')
    assert not state.alive


def test_other_close_error_remains_failure_despite_normal_process_exit(runtime, tmp_path):
    state = runtime(close_error=RuntimeError('not a lost ACK'))
    with pytest.raises(RuntimeError, match='not a lost ACK'):
        with engine.office_document(tmp_path / 'source.docx', 'docx'):
            pass
    assert not state.alive
