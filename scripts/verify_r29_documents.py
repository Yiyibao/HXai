"""Real local cloud worker -> document gateway -> LibreOffice -> independent output validator.

Synthetic pinned provider only. Local owned processes are not Linux container isolation.
No production service, real account, dependency installation or private source is used.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
from pathlib import Path
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from cryptography.hazmat.primitives.asymmetric import rsa
from verify_r27_android import until
from verify_b_xlsx_readback import make_source,parts,package,X

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'server/agent-runtime'))
from hxai_agent.authority import NetworkAuthority,NetworkStore
from hxai_agent.store import canonical
from hxai_agent.vault import TaskVault
from hxai_agent.worker import Client
from hxai_agent.documents import CHUNK
SECRET='SYNTHETIC-R29-DOCUMENT-PROVIDER-NOT-REAL'


def port():
    with socket.socket() as stream:stream.bind(('127.0.0.1',0));return stream.getsockname()[1]


class Provider(BaseHTTPRequestHandler):
    wire_model='locked-wire'
    secret=SECRET
    def log_message(self,*_):pass
    def do_POST(self):
        try:
            body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            assert self.headers['Authorization']=='Bearer '+self.secret
            assert body['model']==self.wire_model and body['reasoning_effort']=='high'
            messages=body['messages'];system=messages[0].get('content','');tool=self.server.tool
            if system.startswith('HXAI_TASK_PLAN_V6'):
                reply=canonical(dict(steps=[dict(id='document-output',tool=tool,purpose='Real original document service')],
                    acceptance=[dict(stepId='document-output',kind='artifact_content',format='pdf' if tool=='render_office_pdf' else 'xlsx',contains=['Count' if tool=='render_office_pdf' else '21'])],explanation='Actual output required'))
                message=dict(role='assistant',content=reply)
            elif system.startswith('HXAI_GOAL_VERIFY_V6'):
                evidence=json.loads(messages[-1]['content']);assert evidence['evidence'][0]['artifact']['format'] in {'pdf','xlsx'}
                message=dict(role='assistant',content=canonical(dict(accepted=True,goalRevision=evidence['goalRevision'],evidenceHash=evidence['evidenceHash'],reason='Synthetic fixture: independent evidence required')))
            elif any(m['role']=='tool' for m in messages):message=dict(role='assistant',content='Actual document-service output independently verified.')
            else:
                selected=json.loads(next(m['content'] for m in messages if m['role']=='user' and '"sources"' in m['content']))['sources'][0]
                message=dict(role='assistant',content='',tool_calls=[dict(id='actual-document-call',type='function',function=dict(name=tool,arguments=canonical(dict(material_id=selected['id'],expected_sha256=selected['sha256']))))])
            self.server.requests.append(dict(requestId=self.headers.get('Idempotency-Key'),model=body['model'],reasoning=body['reasoning_effort'],bodySha256=hashlib.sha256(canonical(body).encode()).hexdigest()))
            data=canonical(dict(choices=[dict(message=message)],usage=dict(prompt_tokens=17,completion_tokens=11))).encode()
            self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
        except Exception:
            self.server.failed.set();self.send_error(500,'synthetic_fixture_rejected')


class LostUploadAck(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*_):pass
    def forward(self):
        data=self.rfile.read(int(self.headers.get('Content-Length','0'))) if self.command=='POST' else None
        connection=http.client.HTTPConnection('127.0.0.1',self.server.upstream,timeout=150)
        try:
            headers={k:v for k,v in self.headers.items() if k.lower() in {'authorization','content-type','idempotency-key','x-source-sha256'}}
            connection.request(self.command,self.path,body=data,headers=headers)
            response=connection.getresponse();body=response.read(31*1024*1024);status=response.status
        finally:connection.close()
        if self.command=='POST' and self.path.startswith('/api/v1/conversions?'):
            self.server.uploads+=1
            if self.server.uploads==1 and status==202:
                self.close_connection=True
                self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
        self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    do_GET=forward
    do_POST=forward


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run-dir',type=Path,required=True);args=parser.parse_args()
    folder=args.run_dir.resolve();folder.mkdir(parents=True,exist_ok=False)
    private=folder/'private';private.mkdir(mode=0o700);(private/'.gitignore').write_text('*\n')
    original=folder/'original.xlsx';source=folder/'changed-source.xlsx';make_source(original)
    content=parts(original);sheet=ET.fromstring(content['xl/worksheets/sheet1.xml']);sheet.find(f'.//{{{X}}}c[@r="A2"]/{{{X}}}v').text='7'
    content['xl/worksheets/sheet1.xml']=ET.tostring(sheet);package(source,content);payload=source.read_bytes();sourcehash=hashlib.sha256(payload).hexdigest()
    children=[];logs=[];fault=None
    def launch(command,cwd):
        log=(folder/f'owned-{len(children)}.log').open('xb');logs.append(log)
        child=subprocess.Popen(list(map(str,command)),cwd=cwd,stdout=log,stderr=subprocess.STDOUT);children.append(child);return child
    provider=ThreadingHTTPServer(('127.0.0.1',0),Provider);provider.tool='render_office_pdf';provider.requests=[];provider.failed=threading.Event()
    threading.Thread(target=provider.serve_forever,daemon=True).start()
    try:
        documentport=port();documentroot=private/'document-service'
        documents=launch([sys.executable,'scripts/verify_b_xlsx_service.py','--run-dir',documentroot,'--port',documentport],ROOT)
        def document_ready():
            if documents.poll() is not None:raise RuntimeError('Owned document service exited')
            return (documentroot/'fixture-identities.json').exists()
        until(document_ready,30);identity=json.loads((documentroot/'fixture-identities.json').read_text())
        authorityroot=private/'authority';authorityroot.mkdir();key=private/'vault.key';TaskVault.initialize(key)
        authority=NetworkAuthority(NetworkStore(authorityroot/'authority.sqlite3'));token=authority.enroll('r29-phone',['owner'])
        signing=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        from cryptography.hazmat.primitives import serialization
        authority.pair(token,dict(publicKey=signing.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()))
        authorityport=port();server=launch([sys.executable,'-m','hxai_agent.service','serve','--root',authorityroot,'--key-file',key,'--port',authorityport,'--loopback-http','--worker'],ROOT/'server/agent-runtime')
        client=Client(f'http://127.0.0.1:{authorityport}',token,loopback_http=True)
        def ready():
            if server.poll() is not None:raise RuntimeError('Owned authority exited')
            try:return client.rpc('domain.status',{})
            except OSError:return None
        until(ready,20)
        upload=None
        for offset in range(0,len(payload),CHUNK):
            value=dict(owner='owner',sourceId='synthetic-source',sha256=sourcehash,extension='xlsx',size=len(payload),offset=offset,bytes=base64.b64encode(payload[offset:offset+CHUNK]).decode(),consent=True)
            upload=client.rpc('source.chunk',value,'chunk-'+str(offset))
            assert client.rpc('source.chunk',value,'chunk-'+str(offset))==upload
        assert upload['complete']
        results=[]
        for tool in ['render_office_pdf','recalculate_xlsx']:
            provider.tool=tool
            value=dict(owner='owner',sourceId='cloud-'+tool,goal='Use '+tool+' on the selected immutable source and independently verify the actual output.',
                model=dict(provider_id='locked-provider',model_id='locked-model',reasoning='HIGH',configuration_revision='original-configuration'),
                configuration=dict(protocol='openai_chat',endpoint=f'http://127.0.0.1:{provider.server_port}/v1/chat/completions',wireModel='locked-wire',baseBody={'reasoning_effort':'high'},headers={},secret=SECRET),
                tools=[],requireEvidence=True,sources=[dict(id='synthetic-source',name='Changed immutable source.xlsx',sha256=sourcehash,extension='xlsx',size=len(payload),uploadId=upload['uploadId'])],
                documentService=dict(endpoint=f'http://127.0.0.1:{documentport}',secret=identity['token'],consent=True),
                grant=dict(mode='UNTIL_REVOKED',expiresAt=None,dataScope='THIS_GOAL_SELECTED_SOURCES_AND_PINNED_PROVIDER'))
            task=client.rpc('cloud.create',value,'create-'+tool)['taskId']
            def completed():
                observed=client.rpc('task.read',dict(owner='owner',taskId=task))
                if observed['state']=='FAILED_BLOCKED' and not observed['executorActive']:raise RuntimeError('Real cloud document path failed; inspect durable task/call/remote records')
                return observed if observed['state']=='SUCCEEDED' and not observed['executorActive'] else None
            state=until(completed,180)
            artifact=state['result']['artifacts'][0];data=bytearray();offset=0
            while True:
                received=client.rpc('artifact.read',dict(owner='owner',taskId=task,artifactId=artifact['id'],offset=offset))
                assert received['offset']==offset and received['sourceSha256']==sourcehash
                data.extend(base64.b64decode(received['bytes'],validate=True));offset=received['nextOffset']
                if offset==received['size']:break
            assert hashlib.sha256(data).hexdigest()==received['sha256']
            output=folder/(tool+'.'+received['extension']);output.write_bytes(data)
            if tool=='render_office_pdf':
                from pypdf import PdfReader
                assert 'Count' in '\n'.join(p.extract_text() for p in PdfReader(output,strict=True).pages)
            else:
                saved=ET.fromstring(parts(output)['xl/worksheets/sheet1.xml'])
                assert float(saved.find(f'.//{{{X}}}c[@r="B2"]/{{{X}}}v').text)==21
            with authority.store.transaction() as db:
                journal=dict(db.execute('SELECT * FROM net_document_calls WHERE task_id=?',(task,)).fetchone())
                assert journal['state']=='VERIFIED' and db.execute('SELECT task_id FROM execution_slot').fetchone()[0] is None
            with sqlite3.connect(documentroot/'data/tasks.sqlite3') as db:
                assert db.execute('SELECT count(*) FROM jobs WHERE id=? AND worker_stopped=1',(journal['remote_id'],)).fetchone()[0]==1
            results.append(dict(tool=tool,status='PASS',taskId=task,remoteTaskId=journal['remote_id'],artifactSha256=received['sha256'],sourceSha256=sourcehash))
        fault=ThreadingHTTPServer(('127.0.0.1',0),LostUploadAck);fault.upstream=documentport;fault.uploads=0
        threading.Thread(target=fault.serve_forever,daemon=True).start()
        provider.tool='render_office_pdf'
        value=dict(value,sourceId='cloud-lost-upload-ack',goal='Render and verify the selected source after explicit recovery.',
            documentService=dict(endpoint=f'http://127.0.0.1:{fault.server_port}',secret=identity['token'],consent=True))
        task=client.rpc('cloud.create',value,'lost-upload-ack')['taskId']
        def independently_settled():
            state=client.rpc('task.read',dict(owner='owner',taskId=task))
            return state if state['state']=='FAILED_BLOCKED' and any(c['state']=='SUCCEEDED' for c in state['calls']) and not state['executorActive'] else None
        settled=until(independently_settled,180)
        assert fault.uploads==1 and len(provider.requests)==10
        client.rpc('task.control',dict(owner='owner',taskId=task,revision=settled['revision'],action='resume',payload=''),'explicit-resume-after-readback')
        state=until(lambda:(lambda s:s if s['state']=='SUCCEEDED' and not s['executorActive'] else None)(client.rpc('task.read',dict(owner='owner',taskId=task))),90)
        assert fault.uploads==1
        with sqlite3.connect(documentroot/'data/tasks.sqlite3') as db:assert db.execute('SELECT count(*) FROM jobs').fetchone()[0]==3
        artifact=state['result']['artifacts'][0]
        received=client.rpc('artifact.read',dict(owner='owner',taskId=task,artifactId=artifact['id']))
        output=base64.b64decode(received['bytes'],validate=True)
        assert hashlib.sha256(output).hexdigest()==artifact['sha256']
        (folder/'lost-ack-output.pdf').write_bytes(output)
        results.append(dict(tool='render_office_pdf_lost_upload_ack',status='PASS',uploads=fault.uploads,taskId=task,artifactSha256=artifact['sha256'],recovery='ORIGINAL_REQUEST_LOOKUP_READBACK_THEN_EXPLICIT_RESUME'))
        assert len(provider.requests)==12 and len({p['requestId'] for p in provider.requests})==12 and not provider.failed.is_set()
        assert hashlib.sha256(source.read_bytes()).hexdigest()==sourcehash
        (folder/'RESULT.json').write_text(json.dumps(dict(status='PASS',cases=results,providerRequests=provider.requests,containerIsolation='NOT_RUN',minerU='NOT_RUN'),indent=2))
        print(json.dumps(dict(status='PASS',tools=[r['tool'] for r in results])))
    finally:
        for child in reversed(children):
            if child.poll() is None:child.terminate();child.wait(timeout=35)
        provider.shutdown();provider.server_close()
        if fault:fault.shutdown();fault.server_close()
        for log in logs:log.close()
    return 0

if __name__=='__main__':raise SystemExit(main())
