"""Production main/claim/execute with controlled OS/Docker boundaries, not worker QA."""
import importlib
import os
import sys
import threading
import types
import hashlib
import pytest
from api.store import Store

def test_main_claim_execute_interleaves_enqueue_and_cleanup(tmp_path, monkeypatch):
    if os.name == 'nt':
        monkeypatch.setitem(sys.modules, 'fcntl', types.SimpleNamespace(LOCK_EX=1, LOCK_NB=2, flock=lambda *_: None))
    dispatcher = importlib.import_module('dispatcher.main')
    store = Store(tmp_path); owner, _ = store.issue()
    def enqueue(key):
        source = tmp_path/'uploads'/f'{key}.pdf'; data=b'%PDF synthetic '+key.encode(); source.write_bytes(data)
        options=dict(sourceSha256=hashlib.sha256(data).hexdigest(), extension='pdf', pages=[1,1], tier='basic',parserVersion='qa',adapterVersion='qa')
        return store.enqueue(owner,key,'fingerprint-'+key,options,source)
    first = enqueue('first')
    entered, release = threading.Event(), threading.Event()
    calls=[]; errors=[]
    class StopLoop(Exception): pass
    def docker(*args, **kwargs):
        calls.append(args)
        if args[0]=='create':
            if not entered.is_set():
                entered.set(); assert release.wait(10)
            raise RuntimeError('QA Docker boundary unavailable')
        return types.SimpleNamespace(returncode=0, stdout='')
    monkeypatch.setattr(dispatcher,'ROOT',tmp_path)
    monkeypatch.setattr(dispatcher,'docker',docker)
    monkeypatch.setattr(dispatcher.subprocess,'run',lambda *_args,**_kwargs: types.SimpleNamespace(returncode=0))
    monkeypatch.setattr(dispatcher.os,'chown',lambda *_:None, raising=False)
    monkeypatch.setattr(dispatcher.time,'sleep',lambda _: (_ for _ in ()).throw(StopLoop()))
    def main():
        try: dispatcher.main()
        except StopLoop: pass
        except BaseException as e: errors.append(e)
    thread=threading.Thread(target=main); thread.start()
    try:
        assert entered.wait(10)
        assert store.get(owner,first['id'])['state']=='RUNNING'
        second=enqueue('second')
        dispatcher.cleanup(store)
        assert store.get(owner,second['id'])['state']=='QUEUED'
        for job in (first,second):
            directory=tmp_path/'jobs'/job['id']
            assert (directory/'input/source.pdf').read_bytes().startswith(b'%PDF synthetic')
            assert (directory/'input/request.json').is_file()
    finally:
        release.set(); thread.join(10)
    assert not errors and not thread.is_alive()
    assert store.get(owner,first['id'])['state']=='FAILED'
    assert store.get(owner,second['id'])['state']=='FAILED'
    assert len([c for c in calls if c[0]=='create'])==2
    assert len([c for c in calls if c[0]=='rm'])>=4
