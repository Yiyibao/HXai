"""Owned loopback TLS, real supervised executor, host crash and OS-exit recovery."""
from __future__ import annotations
import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import ipaddress
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from verify_r27_android import Provider, SECRET, CONTENT, until

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'server/agent-runtime'))
from hxai_agent.authority import NetworkAuthority,NetworkStore
from hxai_agent.store import canonical
from hxai_agent.vault import TaskVault
from hxai_agent.worker import Client,process_birth


def certificates(folder):
    now=datetime.now(timezone.utc)
    def ca(name):
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,name)])
        cert=(x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(days=1)).not_valid_after(now+timedelta(days=30))
            .add_extension(x509.BasicConstraints(ca=True,path_length=0),critical=True)
            .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()),critical=False)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(key.public_key()),critical=False)
            .add_extension(x509.KeyUsage(True,False,False,False,False,True,True,False,False),critical=True).sign(key,hashes.SHA256()))
        path=folder/(name+'.pem');path.write_bytes(cert.public_bytes(serialization.Encoding.PEM));return key,cert,path
    rootkey,rootcert,rootpath=ca('owned-ca');_,_,wrong=ca('wrong-ca')
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    keypath=folder/'leaf.key';keypath.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    for expired in (False,True):
        cert=(x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'localhost')]))
            .issuer_name(rootcert.subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now-timedelta(days=10)).not_valid_after(now+timedelta(days=-1 if expired else 2))
            .add_extension(x509.BasicConstraints(ca=False,path_length=None),critical=True)
            .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()),critical=False)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(rootkey.public_key()),critical=False)
            .add_extension(x509.KeyUsage(True,False,True,False,False,False,False,False,False),critical=True)
            .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]),critical=False)
            .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost'),x509.IPAddress(ipaddress.ip_address('::1'))]),critical=False).sign(rootkey,hashes.SHA256()))
        (folder/('expired.pem' if expired else 'leaf.pem')).write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    return rootpath,wrong,keypath


class BlockingProvider(Provider):
    def do_POST(self):
        self.server.entered.set()
        super().do_POST()


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run-dir',type=Path,required=True)
    parser.add_argument('--ipv6',action='store_true',help='Also qualify IPv6 on a host that permits IPv6 loopback connections')
    args=parser.parse_args()
    folder=args.run_dir.resolve();folder.mkdir(exist_ok=False,parents=True)
    private=folder/'private';private.mkdir(mode=0o700);(private/'.gitignore').write_text('*\n')
    ca,wrong,key=certificates(private)
    root=private/'authority';root.mkdir();vault=private/'vault.key';TaskVault.initialize(vault)
    authority=NetworkAuthority(NetworkStore(root/'authority.sqlite3'));token=authority.enroll('owned-phone',['owner'])
    provider=ThreadingHTTPServer(('127.0.0.1',0),BlockingProvider)
    provider.native=False;provider.requests=[];provider.release=threading.Event();provider.failed=threading.Event();provider.entered=threading.Event()
    threading.Thread(target=provider.serve_forever,daemon=True).start()
    children=[];logs=[];results=[]
    def launch(cert,port=None,*,host='127.0.0.1'):
        if port is None:
            with socket.socket(socket.AF_INET6 if ':' in host else socket.AF_INET) as s:s.bind((host,0));port=s.getsockname()[1]
        command=[sys.executable,'-m','hxai_agent.service','serve','--root',str(root),'--key-file',str(vault),'--host',host,'--port',str(port),
            '--cert-file',str(cert),'--tls-key-file',str(key),'--worker-ca-file',str(ca),'--loopback-http','--worker']
        if host=='127.0.0.1':command+=['--worker-url',f'https://localhost:{port}']
        log=(folder/f'authority-{len(children)}.log').open('xb');logs.append(log)
        process=subprocess.Popen(command,cwd=ROOT/'server/agent-runtime',stdout=log,stderr=subprocess.STDOUT);children.append(process)
        def ready():
            if process.poll() is not None:raise RuntimeError('Owned authority startup failed')
            try:
                with socket.create_connection((host,port),timeout=.2):return True
            except OSError:return False
        until(ready,20);return process,port
    try:
        server,port=launch(private/'leaf.pem')
        client=Client(f'https://localhost:{port}',token,ca_file=ca)
        signing=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        req=urllib.request.Request(client.endpoint+'/v1/pair',data=canonical(dict(publicKey=signing.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode())).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
        with client.opener.open(req,timeout=10) as response:assert json.load(response)['owners']==['owner']
        assert client.rpc('domain.status',{})['retired'] is False
        for label,endpoint,trust,code in [('wrong-ca',client.endpoint,wrong,None),('wrong-hostname',f'https://127.0.0.1:{port}',ca,64)]:
            try:Client(endpoint,token,ca_file=trust).rpc('domain.status',{})
            except urllib.error.URLError as error:
                assert isinstance(error.reason,ssl.SSLCertVerificationError)
                if code is not None:assert error.reason.verify_code==code
                results.append(dict(case=label,status='PASS',verifyCode=error.reason.verify_code))
            else:raise AssertionError('Invalid TLS certificate accepted')
        for endpoint in [f'http://localhost:{port}','http://not-loopback.invalid']:
            try:Client(endpoint,token)
            except ValueError:pass
            else:raise AssertionError('Implicit plaintext accepted')
        results.append(dict(case='no-http-downgrade',status='PASS'))
        expired,expiredport=launch(private/'expired.pem')
        try:Client(f'https://localhost:{expiredport}',token,ca_file=ca).rpc('domain.status',{})
        except urllib.error.URLError as error:
            assert isinstance(error.reason,ssl.SSLCertVerificationError) and error.reason.verify_code==10
            results.append(dict(case='expired-certificate',status='PASS',verifyCode=10))
        else:raise AssertionError('Expired certificate accepted')
        expired.terminate();expired.wait(timeout=25)
        if args.ipv6:
            ipv6,ipv6port=launch(private/'leaf.pem',host='::1')
            assert Client(f'https://[::1]:{ipv6port}',token,ca_file=ca).rpc('domain.status',{})['retired'] is False
            ipv6.terminate();ipv6.wait(timeout=25)
            results.append(dict(case='ipv6-default-worker-tls-origin',status='PASS'))
        value=dict(owner='owner',sourceId='owned-tls-crash',goal='Write and independently verify the requested Markdown version.',
            model=dict(provider_id='pinned-provider',model_id='pinned-model',reasoning='HIGH',configuration_revision='original-revision'),
            configuration=dict(protocol='openai_chat',endpoint=f'http://127.0.0.1:{provider.server_port}/v1/chat/completions',wireModel='synthetic-locked-model',
                baseBody={'reasoning_effort':'high'},headers={},secret=SECRET),tools=[],sources=[],requireEvidence=True,
            grant=dict(mode='UNTIL_REVOKED',expiresAt=None,dataScope='THIS_GOAL_SELECTED_SOURCES_AND_PINNED_PROVIDER'))
        task=client.rpc('cloud.create',value,'create-once')['taskId']
        assert provider.entered.wait(20)
        with authority.store.transaction() as db:
            old=dict(db.execute("SELECT * FROM net_processes WHERE state='RUNNING'").fetchone())
            assert db.execute("SELECT state FROM net_provider_calls WHERE task_id=?",(task,)).fetchone()[0]=='IN_FLIGHT'
        assert process_birth(old['pid'])==old['birth']
        server.kill();server.wait(timeout=15)  # An actual host crash, not graceful child cancellation.
        assert process_birth(old['pid'])==old['birth']
        restarted,_=launch(private/'leaf.pem',port)
        time.sleep(.6)
        with authority.store.transaction() as db:
            assert db.execute('SELECT task_id FROM execution_slot').fetchone()[0]==task
            assert db.execute('SELECT count(*) FROM net_processes').fetchone()[0]==1
        provider.release.set()
        def completed():
            state=client.rpc('task.read',dict(owner='owner',taskId=task))
            if state['state'] in {'FAILED_BLOCKED','UNCERTAIN_SIDE_EFFECT'}:raise AssertionError(state['state'])
            return state if state['state']=='SUCCEEDED' and not state['executorActive'] else None
        state=until(completed,90)
        assert len(provider.requests)==4 and len({p['requestId'] for p in provider.requests})==4
        assert not provider.failed.is_set()
        artifact=state['result']['artifacts'][0]
        received=client.rpc('artifact.read',dict(owner='owner',taskId=task,artifactId=artifact['id']))
        data=base64.b64decode(received['bytes'],validate=True)
        assert data.decode()==CONTENT and hashlib.sha256(data).hexdigest()==received['sha256']
        (folder/'actual-output.md').write_bytes(data)
        assert client.rpc('cloud.create',value,'create-once')['taskId']==task
        with authority.store.transaction() as db:
            assert db.execute('SELECT task_id FROM execution_slot').fetchone()[0] is None
            assert db.execute('SELECT count(*) FROM net_processes').fetchone()[0]==1
        results.append(dict(case='tls-host-crash-live-worker-adoption-exit',status='PASS',originalHostPid=server.pid,restartedHostPid=restarted.pid,
            workerPid=old['pid'],physicalRequests=provider.requests,artifactSha256=received['sha256']))
        (folder/'RESULT.json').write_text(json.dumps(dict(status='PASS',cases=results),indent=2));print(json.dumps(dict(status='PASS',cases=[r['case'] for r in results])))
    finally:
        provider.release.set()
        for process in reversed(children):
            if process.poll() is None:process.terminate();process.wait(timeout=30)
        provider.shutdown();provider.server_close()
        for log in logs:log.close()
    return 0

if __name__=='__main__':raise SystemExit(main())
