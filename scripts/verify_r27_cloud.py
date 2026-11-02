"""Real HTTP + separately supervised cloud worker, with a synthetic loopback Provider.

Exercises physical requests, original Runner file publication and independent artifact
readback after the submitting client exits. This is not real-model quality evidence.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
sys.path.insert(0,str(ROOT/'server/agent-runtime'))
from verify_r27_authority import post,rpc,private
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from hxai_agent.authority import NetworkAuthority,NetworkStore
from hxai_agent.store import canonical

SECRET='SYNTHETIC-R27-KEY-NEVER-A-REAL-PROVIDER-CREDENTIAL'
CONTENT='# Synthetic cloud result\nVerified after the submitting client exited.\n'


class Provider(BaseHTTPRequestHandler):
    def log_message(self,*_):pass
    def do_POST(self):
        raw=self.rfile.read(int(self.headers['Content-Length']))
        body=json.loads(raw)
        assert self.headers.get('Authorization')=='Bearer '+SECRET
        assert body['model']=='synthetic-locked-model' and body['reasoning_effort']=='high'
        with self.server.lock:
            self.server.requests.append({'requestId':self.headers.get('Idempotency-Key'),'bodyHash':hashlib.sha256(raw).hexdigest(),'path':self.path})
        if self.path=='/unknown':
            self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
        messages=body['messages'];system=messages[0].get('content','')
        if system.startswith('HXAI_TASK_PLAN_V6'):
            text=canonical({'steps':[{'id':'result','tool':'write_report_version','purpose':'Create the requested immutable Markdown file'}],
                'acceptance':[{'stepId':'result','kind':'artifact_content','format':'md','contains':['Verified after the submitting client exited.']}],
                'explanation':'One actual versioned write, followed by independent readback.'})
            message={'role':'assistant','content':text}
        elif system.startswith('HXAI_GOAL_VERIFY_V6'):
            evidence=json.loads(messages[-1]['content'])
            assert evidence['evidence'][0]['artifact']['content']==CONTENT
            message={'role':'assistant','content':canonical({'accepted':True,'goalRevision':evidence['goalRevision'],'evidenceHash':evidence['evidenceHash'],'reason':'Synthetic fixture evaluator; not a real model score.'})}
        elif any(m['role']=='tool' for m in messages):
            message={'role':'assistant','content':'The synthetic Markdown file has been independently read back.'}
        else:
            message={'role':'assistant','content':'','tool_calls':[{'id':'synthetic-write','type':'function','function':{'name':'write_report_version','arguments':canonical({'family':'lesson','content':CONTENT,'parent_artifact_id':None})}}]}
        value={'choices':[{'message':message}]}
        if system.startswith('HXAI_'):value['usage']={'prompt_tokens':11,'completion_tokens':7}
        data=json.dumps(value).encode()
        self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)


def submit(spec):
    value=json.loads(Path(spec).read_text())
    status,result=rpc(value['endpoint'],value['token'],'cloud.create',value['request'],'create-once')
    assert status==200
    print(json.dumps(result),flush=True)
    return 0


def scenario(folder,provider,unknown=False):
    folder.mkdir()
    private_root=folder/'private';private_root.mkdir(mode=0o700);(private_root/'.gitignore').write_text('*\n')
    authority_root=private_root/'authority';key=private_root/'wrapping.key'
    command=[sys.executable,'-m','hxai_agent.service','init','--root',str(authority_root),'--key-file',str(key)]
    initialized=subprocess.run(command,cwd=ROOT/'server/agent-runtime',capture_output=True,timeout=30)
    (folder/'init.log').write_bytes(initialized.stdout+initialized.stderr);assert initialized.returncode==0
    authority=NetworkAuthority(NetworkStore(authority_root/'authority.sqlite3'))
    token=authority.enroll('synthetic-phone',['synthetic-owner'])
    signing=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    pem=signing.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    endpoint=f'http://127.0.0.1:{port}'
    serving=[sys.executable,'-m','hxai_agent.service','serve','--root',str(authority_root),'--key-file',str(key),'--port',str(port),'--loopback-http','--worker']
    log=(folder/'server.log').open('xb');server=subprocess.Popen(serving,cwd=ROOT/'server/agent-runtime',stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,PYTHONUTF8='1'))
    try:
        deadline=time.monotonic()+20
        while True:
            assert server.poll() is None
            try:
                with socket.create_connection(('127.0.0.1',port),timeout=.1):break
            except OSError:
                if time.monotonic()>deadline:raise RuntimeError('owned_server_readiness_timeout')
                time.sleep(.05)
        assert post(endpoint,token,'/v1/pair',{'publicKey':pem})[0]==200
        request={'owner':'synthetic-owner','sourceId':'synthetic-original-chat-task',
            'goal':'Create an immutable Markdown file containing: Verified after the submitting client exited.',
            'model':{'provider_id':'synthetic-provider','model_id':'accepted-model-id','reasoning':'HIGH','configuration_revision':'immutable-native-snapshot'},
            'configuration':{'protocol':'openai_chat','endpoint':f'http://127.0.0.1:{provider.server_port}/'+('unknown' if unknown else 'chat/completions'),
                'wireModel':'synthetic-locked-model','baseBody':{'reasoning_effort':'high'},'headers':{},'secret':SECRET},
            'tools':[],'requireEvidence':True,'grant':{'expiresAt':time.time()+3600,'dataScope':'THIS_GOAL_SELECTED_SOURCES_AND_PINNED_PROVIDER'},'sources':[]}
        spec=private_root/'submitting-client.json';private(spec,{'endpoint':endpoint,'token':token,'request':request})
        child=subprocess.Popen([sys.executable,str(Path(__file__).resolve()),'--submit-spec',str(spec)],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        stdout,stderr=child.communicate(timeout=30)
        (folder/'submit.log').write_bytes(stdout+b'\nSTDERR\n'+stderr);assert child.returncode==0
        task=json.loads(stdout)['taskId']
        deadline=time.monotonic()+45
        while True:
            assert child.poll()==0,'Submitting client must have exited'
            assert server.poll() is None
            status,state=rpc(endpoint,token,'task.read',{'owner':'synthetic-owner','taskId':task})
            assert status==200
            if state['state'] in ('SUCCEEDED','UNCERTAIN_SIDE_EFFECT','FAILED_BLOCKED','WAITING_INPUT','WAITING_AUTHORIZATION'):break
            if time.monotonic()>deadline:raise RuntimeError('cloud_task_timeout')
            time.sleep(.1)
        if unknown:
            assert state['state']=='UNCERTAIN_SIDE_EFFECT',state['state']
            rejected,_=rpc(endpoint,token,'task.control',{'owner':'synthetic-owner','taskId':task,'revision':state['revision'],'action':'resume','payload':''},'must-not-resume')
            assert rejected==409
            with authority.store.transaction() as db:
                assert db.execute('SELECT task_id FROM execution_slot').fetchone()[0]==task
                assert db.execute("SELECT count(*) FROM net_provider_calls WHERE task_id=? AND state='UNKNOWN'",(task,)).fetchone()[0]==1
            time.sleep(.4)
            physical=[r for r in provider.requests if r['path']=='/unknown']
            assert len(physical)==1,'Unknown physical request was retried'
            return {'status':'PASS','scenario':'unknown-physical-request-retains-slot','physicalRequests':1,'resumeRejected':True}
        assert state['state']=='SUCCEEDED',state['state']
        artifact=state['result']['artifacts'][0]
        status,download=rpc(endpoint,token,'artifact.read',{'owner':'synthetic-owner','taskId':task,'artifactId':artifact['id']})
        assert status==200
        data=base64.b64decode(download['bytes']);assert data==CONTENT.encode() and hashlib.sha256(data).hexdigest()==download['sha256']==artifact['sha256']
        (folder/'actual-result.md').write_bytes(data)
        deadline=time.monotonic()+10
        while True:
            with authority.store.transaction() as db:
                processes=[dict(r) for r in db.execute('SELECT * FROM net_processes')]
                slot=db.execute('SELECT task_id FROM execution_slot').fetchone()[0]
            if slot is None:break
            if time.monotonic()>deadline:raise RuntimeError('owned_worker_stop_not_confirmed')
            time.sleep(.05)
        assert processes and all(p['pid'] not in {server.pid,child.pid,os.getpid()} and p['state']=='STOPPED' for p in processes)
        with authority.store.transaction() as db:
            assert db.execute("SELECT count(*) FROM calls WHERE task_id=? AND tool='write_report_version' AND state='SUCCEEDED'",(task,)).fetchone()[0]==1
            assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        for path in authority_root.glob('authority.sqlite3*'):
            assert SECRET.encode() not in path.read_bytes(),'Credential plaintext entered the database'
        assert any(u['provenance']=='unknown' and u['input_tokens'] is None for u in state['usage'])
        assert any(u['provenance']=='provider_reported' and u['input_tokens']==11 for u in state['usage'])
        return {'status':'PASS','scenario':'phone-client-exited-cloud-worker-completed','authorityPid':server.pid,'exitedSubmittingClientPid':child.pid,
            'workerPids':[p['pid'] for p in processes],'artifactSha256':download['sha256'],'physicalProviderRequests':len([r for r in provider.requests if r['path']!='/unknown']),
            'realModelCalls':0,'reportedAndUnknownUsageSeparated':True,'plaintextSecretAbsentFromDatabase':True}
    finally:
        if server.poll() is None:server.terminate();server.wait(timeout=20)
        log.close()


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run-dir',type=Path);p.add_argument('--submit-spec',type=Path)
    args=p.parse_args()
    if args.submit_spec:return submit(args.submit_spec)
    if args.run_dir is None:p.error('--run-dir required')
    folder=args.run_dir.absolute();folder.mkdir(parents=True,exist_ok=False)
    provider=ThreadingHTTPServer(('127.0.0.1',0),Provider);provider.lock=threading.Lock();provider.requests=[]
    thread=threading.Thread(target=provider.serve_forever,daemon=True);thread.start()
    try:
        results=[scenario(folder/'completed',provider),scenario(folder/'unknown',provider,True)]
        report={'status':'PASS_SCOPED','scope':'Independent cloud worker and actual loopback Provider transport, original file Runner and independent artifact bytes; synthetic model responses, not mathematical/real Provider quality',
                'results':results,'physicalRequests':provider.requests}
        (folder/'RESULT.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(report,ensure_ascii=False))
    finally:
        provider.shutdown();provider.server_close();thread.join(timeout=5)
    return 0


if __name__=='__main__':raise SystemExit(main())
