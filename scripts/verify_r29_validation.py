"""Owned local credential validation: real HTTP, physical worker death and late rejection."""
from __future__ import annotations
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import threading

from verify_r27_android import until, SECRET
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'server/agent-runtime'))
from hxai_agent.authority import NetworkAuthority, NetworkStore
from hxai_agent.store import canonical
from hxai_agent.vault import TaskVault
from hxai_agent.worker import Client


class Provider(BaseHTTPRequestHandler):
    def log_message(self,*_):pass
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        assert self.headers['Authorization']=='Bearer '+SECRET
        assert body['model']=='synthetic-locked-model' and body['reasoning_effort']=='high'
        self.server.requests.append(self.headers['Idempotency-Key'])
        self.server.entered.set()
        assert self.server.release.wait(60)
        status=401 if self.server.reject else 200
        data=canonical({'error':{'message':'synthetic rejection'}} if status==401 else {'choices':[{'message':{'role':'assistant','content':'OK'}}]}).encode()
        self.send_response(status);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)


class BeforeApply(BaseHTTPRequestHandler):
    def log_message(self,*_):pass
    def do_POST(self):
        data=self.rfile.read(int(self.headers['Content-Length']));value=json.loads(data)
        if value['operation']=='worker.validation.apply' and not self.server.injected:
            self.server.injected=True;self.server.entered.set()
            self.server.release.wait(60)
            self.close_connection=True
            return
        connection=http.client.HTTPConnection('127.0.0.1',self.server.upstream,timeout=30)
        try:
            connection.request('POST',self.path,body=data,headers={'Content-Type':'application/json','Authorization':self.headers['Authorization']})
            response=connection.getresponse();body=response.read();status=response.status
        finally:connection.close()
        self.send_response(status);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run-dir',type=Path,required=True);args=parser.parse_args()
    folder=args.run_dir.resolve();folder.mkdir(parents=True,exist_ok=False)
    private=folder/'private';private.mkdir();(private/'.gitignore').write_text('*\n')
    root=private/'authority';root.mkdir();key=private/'vault.key';TaskVault.initialize(key)
    authority=NetworkAuthority(NetworkStore(root/'authority.sqlite3'));token=authority.enroll('owned-phone',['owner'])
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    signing=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    authority.pair(token,dict(publicKey=signing.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()))
    provider=ThreadingHTTPServer(('127.0.0.1',0),Provider);provider.requests=[];provider.reject=False
    provider.entered=threading.Event();provider.release=threading.Event();provider.release.set()
    proxy=ThreadingHTTPServer(('127.0.0.1',0),BeforeApply);proxy.entered=threading.Event();proxy.release=threading.Event();proxy.injected=False
    for server in (provider,proxy):threading.Thread(target=server.serve_forever,daemon=True).start()
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    proxy.upstream=port
    command=[sys.executable,'-m','hxai_agent.service','serve','--root',str(root),'--key-file',str(key),'--port',str(port),
        '--loopback-http','--worker','--worker-url',f'http://127.0.0.1:{proxy.server_port}']
    log=(folder/'authority.log').open('xb');host=subprocess.Popen(command,cwd=ROOT/'server/agent-runtime',stdout=log,stderr=subprocess.STDOUT)
    client=Client(f'http://127.0.0.1:{port}',token,loopback_http=True)
    def ready():
        if host.poll() is not None:raise RuntimeError('owned authority exited')
        try:
            with socket.create_connection(('127.0.0.1',port),timeout=.2):return True
        except OSError:return False
    def read(task):return client.rpc('task.read',dict(owner='owner',taskId=task))
    def create(source):
        return client.rpc('cloud.create',dict(owner='owner',sourceId=source,goal='Remain paused; validate only.',model=dict(provider_id='synthetic',model_id='locked',reasoning='HIGH',configuration_revision='original-revision'),
            configuration=dict(protocol='openai_chat',endpoint=f'http://127.0.0.1:{provider.server_port}/v1/chat/completions',wireModel='synthetic-locked-model',baseBody={'reasoning_effort':'high'},headers={},secret=SECRET),
            tools=[],sources=[],requireEvidence=False,initialControl=dict(manualPause=True,inputBlocked=True,deviceBlocked=True,deviceConfirmation=True),
            grant=dict(mode='UNTIL_REVOKED',expiresAt=None,dataScope='THIS_GOAL_SELECTED_SOURCES_AND_PINNED_PROVIDER')),source)['taskId']
    def replace(task,command):return client.rpc('credential.replace',dict(owner='owner',taskId=task,revision=read(task)['revision'],secret=SECRET),command)
    try:
        until(ready,20);task=create('crash-before-apply');validation=replace(task,'first-key')['validationTaskId']
        assert proxy.entered.wait(30)
        with authority.store.transaction() as db:
            processes=db.execute("SELECT * FROM net_processes WHERE state='RUNNING'").fetchall()
            assert len(processes)==1
            process=dict(processes[0])
            assert db.execute('SELECT state FROM net_provider_calls WHERE task_id=?',(validation,)).fetchone()[0]=='RETURNED'
        os.kill(process['pid'],signal.SIGTERM);proxy.release.set()
        until(lambda:read(validation)['state']=='FAILED_BLOCKED' and not read(validation)['executorActive'],30)
        assert replace(task,'explicit-recovery')['validationTaskId']==validation
        until(lambda:read(validation)['state']=='SUCCEEDED' and not read(validation)['executorActive'],30)
        assert len(provider.requests)==1
        state=read(task)
        assert state['authorization']=='ACTIVE' and all(state[k] for k in ('manualPaused','inputBlocked','deviceBlocked','deviceConfirmation'))
        # Independent duration renewal cannot restore expired credentials or clear blockers.
        authority.store.change_authorization('owner',task,'EXPIRED')
        client.rpc('hosting.update',dict(owner='owner',taskId=task,revision=read(task)['revision'],grant=dict(mode='UNTIL_REVOKED',expiresAt=None,dataScope='THIS_GOAL_SELECTED_SOURCES_AND_PINNED_PROVIDER')),'duration-only')
        assert read(task)['authorization']=='EXPIRED'
        replace(task,'new-explicit-validation')
        until(lambda:read(task)['authorization']=='ACTIVE' and len(provider.requests)==2,30)
        until(lambda:not read(task)['executorActive'] and all(read(task)[k] for k in ('manualPaused','inputBlocked','deviceBlocked','deviceConfirmation')),30)
        provider.reject=True;provider.entered.clear();provider.release.clear()
        revoked=create('late-rejection');late=replace(revoked,'late-key')['validationTaskId']
        assert provider.entered.wait(30)
        client.rpc('task.control',dict(owner='owner',taskId=revoked,revision=read(revoked)['revision'],action='revoke',payload=''),'revoke-during-validation')
        provider.release.set()
        until(lambda:read(late)['state']=='CANCELED' and not read(late)['executorActive'],30)
        assert read(revoked)['authorization']=='REVOKED'
        with authority.store.transaction() as db:
            assert db.execute('SELECT state FROM net_provider_calls WHERE task_id=?',(late,)).fetchone()[0]=='RETURNED'
            assert db.execute('SELECT credential_revision FROM net_jobs WHERE task_id=?',(revoked,)).fetchone()[0]==1
            assert db.execute('SELECT task_id FROM execution_slot').fetchone()[0] is None
        result=dict(status='PASS',cases=['known-success-real-worker-death-no-replay','duration-renewal-preserves-expired-key-and-four-blockers','revocation-late-rejection-settles-without-key-install'],physicalRequests=provider.requests,killedWorkerPid=process['pid'])
        (folder/'RESULT.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
    finally:
        provider.release.set();proxy.release.set()
        if host.poll() is None:host.terminate();host.wait(timeout=30)
        for server in (provider,proxy):server.shutdown();server.server_close()
        log.close()
    return 0


if __name__=='__main__':raise SystemExit(main())
