"""Owned loopback authority + two independent client processes. Synthetic data only.

No dependency installation, external model, user app data, production server or
credentials are used. A new run directory is required and all owned children exit.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
RUNTIME=ROOT/'server/agent-runtime'
sys.path.insert(0,str(RUNTIME))
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from hxai_agent.authority import NetworkStore, NetworkAuthority
from hxai_agent.store import canonical


def private(path,value):
    fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    with os.fdopen(fd,'w',encoding='utf-8') as stream:json.dump(value,stream)


def post(endpoint,token,path,value):
    req=urllib.request.Request(endpoint+path,data=canonical(value).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},method='POST')
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req,timeout=15) as response:return response.status,json.load(response)
    except urllib.error.HTTPError as error:return error.code,json.loads(error.read())


def rpc(endpoint,token,operation,value,command='synthetic-command'):
    return post(endpoint,token,'/v1/rpc',{'protocolVersion':1,'operation':operation,'commandId':command,'value':value})


def client(specification):
    spec=json.loads(Path(specification).read_text())
    Path(spec['ready']).write_text('ready')
    until=time.monotonic()+20
    while not Path(spec['go']).exists():
        if time.monotonic()>until:raise RuntimeError('owned_start_barrier_timeout')
        time.sleep(.02)
    status,value=rpc(spec['endpoint'],spec['token'],'native.claim',spec['claim'])
    print(json.dumps({'status':status,'result':value}),flush=True)
    return 0 if status==200 else 1


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run-dir',type=Path)
    p.add_argument('--client-spec',type=Path)
    args=p.parse_args()
    if args.client_spec:return client(args.client_spec)
    if args.run_dir is None:p.error('--run-dir is required')
    folder=args.run_dir.absolute();folder.mkdir(parents=True,exist_ok=False)
    secrets_dir=folder/'private';secrets_dir.mkdir(mode=0o700)
    (secrets_dir/'.gitignore').write_text('*\n')
    authority_root=secrets_dir/'authority';key=secrets_dir/'wrapping.key'
    env=dict(os.environ,PYTHONUTF8='1')
    commands=[];children=[];server=None
    def run(label,command):
        result=subprocess.run(list(map(str,command)),cwd=RUNTIME,env=env,capture_output=True,timeout=30)
        log=folder/(label+'.log');log.write_bytes(result.stdout+b'\nSTDERR\n'+result.stderr)
        commands.append({'label':label,'command':list(map(str,command)),'cwd':str(RUNTIME),'exitCode':result.returncode,'sha256':hashlib.sha256(log.read_bytes()).hexdigest()})
        assert result.returncode==0,(label,result.returncode)
    run('initialize',[sys.executable,'-m','hxai_agent.service','init','--root',authority_root,'--key-file',key])
    authority=NetworkAuthority(NetworkStore(authority_root/'authority.sqlite3'))
    actors=[]
    with socket.socket() as reserved:
        reserved.bind(('127.0.0.1',0));port=reserved.getsockname()[1]
    endpoint=f'http://127.0.0.1:{port}'
    def launch(label):
        command=[sys.executable,'-m','hxai_agent.service','serve','--root',str(authority_root),'--key-file',str(key),'--port',str(port),'--loopback-http']
        log=(folder/(label+'.log')).open('xb')
        proc=subprocess.Popen(command,cwd=RUNTIME,env=env,stdout=log,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+20
        while time.monotonic()<deadline:
            if proc.poll() is not None:raise RuntimeError('owned_authority_exited')
            try:
                with socket.create_connection(('127.0.0.1',port),timeout=.2):break
            except OSError:time.sleep(.05)
        else:raise RuntimeError('owned_authority_readiness_timeout')
        commands.append({'label':label,'command':command,'pid':proc.pid,'cwd':str(RUNTIME)})
        return proc,log
    try:
        server,server_log=launch('server-initial')
        model={'provider_id':'synthetic','model_id':'locked','reasoning':'HIGH','configuration_revision':'frozen'}
        for i in range(2):
            token=authority.enroll('device-'+str(i),['synthetic-owner'])
            signing=rsa.generate_private_key(public_exponent=65537,key_size=2048)
            pem=signing.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
            status,_=post(endpoint,token,'/v1/pair',{'publicKey':pem});assert status==200
            value={'owner':'synthetic-owner','sourceId':'source-'+str(i),'goal':'Synthetic native execution gate','inputHash':str(i)*64,'model':model,'grantEpoch':1}
            status,accepted=rpc(endpoint,token,'native.accept',value,'accept-'+str(i));assert status==200
            again_status,again=rpc(endpoint,token,'native.accept',value,'accept-'+str(i))
            assert again_status==200 and again==accepted
            changed=dict(value,inputHash='c'*64)
            assert rpc(endpoint,token,'native.accept',changed,'accept-'+str(i))[0]==409
            actors.append({'token':token,'key':signing,'task':accepted['taskId'],'source':value['sourceId'],'instance':'client-'+str(i)})
            specification={'endpoint':endpoint,'token':token,'ready':str(secrets_dir/f'ready-{i}'),'go':str(secrets_dir/'go'),
                           'claim':{'owner':'synthetic-owner','taskId':accepted['taskId'],'instance':'client-'+str(i)}}
            spec=secrets_dir/f'client-{i}.json';private(spec,specification)
            proc=subprocess.Popen([sys.executable,str(Path(__file__).resolve()),'--client-spec',str(spec)],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            children.append(proc)
        deadline=time.monotonic()+20
        while not all((secrets_dir/f'ready-{i}').exists() for i in range(2)):
            assert all(c.poll() is None for c in children)
            if time.monotonic()>deadline:raise RuntimeError('client_readiness_timeout')
            time.sleep(.02)
        (secrets_dir/'go').write_text('go')
        results=[]
        for i,proc in enumerate(children):
            stdout,stderr=proc.communicate(timeout=20)
            (folder/f'client-{i}.log').write_bytes(stdout+b'\nSTDERR\n'+stderr)
            assert proc.returncode==0
            results.append(json.loads(stdout))
        assert [r['result']['state'] for r in results]==['RUNNING','QUEUED']
        first=actors[0];fence=results[0]['result']['fence']
        base={'owner':'synthetic-owner','taskId':first['task'],'fence':fence,'instance':first['instance']}
        assert rpc(endpoint,first['token'],'execution.check',base)[0]==200
        # Boolean/client assertion and a foreign signing identity cannot retire a host.
        assert rpc(endpoint,first['token'],'native.retire',dict(base,stopped=True))[0]==400
        proof=dict(base,proof={'sourceId':first['source'],'sourceFence':1,'state':'PAUSED_BY_USER','journalHash':'b'*64})
        wrong=dict(proof,signature=base64.b64encode(actors[1]['key'].sign(canonical(proof).encode(),padding.PKCS1v15(),hashes.SHA256())).decode())
        assert rpc(endpoint,first['token'],'native.retire',wrong)[0]==403
        run('revoke-first',[sys.executable,'-m','hxai_agent.service','revoke','--root',authority_root,'--actor','device-0'])
        assert rpc(endpoint,first['token'],'execution.check',base)[0]==403
        signed=dict(proof,signature=base64.b64encode(first['key'].sign(canonical(proof).encode(),padding.PKCS1v15(),hashes.SHA256())).decode())
        assert rpc(endpoint,first['token'],'native.retire',signed)[0]==200
        assert rpc(endpoint,first['token'],'native.retire',signed)[0]==200
        second=actors[1]
        status,lease=rpc(endpoint,second['token'],'native.claim',{'owner':'synthetic-owner','taskId':second['task'],'instance':second['instance']})
        assert status==200 and lease['state']=='RUNNING' and lease['fence']>fence
        base2={'owner':'synthetic-owner','taskId':second['task'],'fence':lease['fence'],'instance':second['instance']}
        assert rpc(endpoint,second['token'],'execution.check',dict(base2,instance='other-process'))[0]==409
        uncertain=dict(base2,proof={'sourceId':second['source'],'sourceFence':1,'state':'UNCERTAIN_SIDE_EFFECT','journalHash':'d'*64})
        uncertain['signature']=base64.b64encode(second['key'].sign(canonical(uncertain).encode(),padding.PKCS1v15(),hashes.SHA256())).decode()
        assert rpc(endpoint,second['token'],'native.retire',uncertain)[0]==409
        # Real server process termination/restart does not release the remote binding.
        server.terminate();server.wait(timeout=15);server_log.close()
        server,server_log=launch('server-restarted')
        assert rpc(endpoint,second['token'],'execution.check',base2)[0]==200
        with authority.store.transaction() as db:
            slot=dict(db.execute('SELECT * FROM execution_slot').fetchone())
            assert slot['task_id']==second['task'] and slot['fence']==lease['fence']
            assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        report={'status':'PASS_SCOPED','scope':'Actual loopback HTTP authority, two independent client processes, real server restart; no App/model/tool-quality claim',
                'authorityPids':[c['pid'] for c in commands if 'pid' in c], 'clientPids':[c.pid for c in children],
                'twoClientResults':results,'oldFence':fence,'newFence':lease['fence'],'unknownSlotRetained':True,
                'forgedStopRejected':True,'revokePreventsDispatch':True,'boundStopAfterRevokeSettled':True,
                'acceptAndStopAckIdempotent':True,'changedIntentRejected':True,'realModelCalls':0,'commands':commands}
        (folder/'RESULT.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({k:report[k] for k in ('status','scope','oldFence','newFence','unknownSlotRetained')}))
    finally:
        for proc in children:
            if proc.poll() is None:proc.terminate();proc.wait(timeout=10)
        if server is not None:
            if server.poll() is None:server.terminate();server.wait(timeout=15)
            server_log.close()
    return 0


if __name__=='__main__':
    raise SystemExit(main())
