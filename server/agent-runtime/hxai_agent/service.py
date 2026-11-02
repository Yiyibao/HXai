"""Versioned network boundary. No admin enrollment, SQL, stop boolean or verifier RPC."""
from __future__ import annotations
import argparse
import base64
import json
import os
from pathlib import Path
import ssl
from urllib.parse import urlparse
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from cryptography.exceptions import InvalidSignature, InvalidTag
from .authority import NetworkStore, NetworkAuthority, fields
from .runner import _reject_reparse_traversal
from .store import Conflict, AuthorizationRequired
from .vault import TaskVault
from .domain import DomainRetired

MAX_BODY=2*1024*1024


def strict_json(raw):
    def unique(pairs):
        result={}
        for key,value in pairs:
            if key in result: raise ValueError('duplicate_json_key')
            result[key]=value
        return result
    value=json.loads(raw,object_pairs_hook=unique,parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite_json')))
    if not isinstance(value,dict): raise ValueError('object_required')
    return value


def create_app(root: Path, key_file: Path, *, loopback_http=False, supervisor=None):
    root=Path(root).absolute()
    _reject_reparse_traversal(root)
    root.mkdir(parents=True,exist_ok=True,mode=0o700)
    store=NetworkStore(root/'authority.sqlite3')
    authority=NetworkAuthority(store)
    app=FastAPI(docs_url=None,redoc_url=None,openapi_url=None)
    # The host is injected only by trusted application setup, never by an RPC caller.
    from .cloud import CloudHost
    cloud=CloudHost(authority,TaskVault(key_file),root/'files',loopback_http=loopback_http)
    app.state.authority=authority
    app.state.cloud=cloud
    app.state.supervisor=supervisor

    @app.middleware('http')
    async def transport_boundary(request: Request, call_next):
        secure=request.url.scheme=='https'
        local=request.client is not None and request.client.host in ('127.0.0.1','::1','testclient')
        if not secure and not (loopback_http and local):
            return JSONResponse({'code':'TLS_REQUIRED'},status_code=403)
        length=request.headers.get('content-length')
        if length and (not length.isdigit() or int(length)>MAX_BODY):
            return JSONResponse({'code':'BODY_LIMIT'},status_code=413)
        return await call_next(request)

    async def body(request):
        raw=bytearray()
        async for part in request.stream():
            raw.extend(part)
            if len(raw)>MAX_BODY: raise ValueError('body_limit')
        return strict_json(bytes(raw))

    def bearer(request):
        value=request.headers.get('authorization','')
        if not value.startswith('Bearer ') or value.count(' ')!=1:
            raise AuthorizationRequired('request_denied')
        return value[7:]

    def failure(error):
        # No private text, endpoints, credentials, parser details or stack traces.
        if isinstance(error,DomainRetired):
            return JSONResponse({'code':'DOMAIN_RETIRED'},status_code=410)
        if isinstance(error,(AuthorizationRequired,InvalidSignature,InvalidTag)):
            return JSONResponse({'code':'REQUEST_DENIED'},status_code=403)
        if isinstance(error,KeyError):
            return JSONResponse({'code':'NOT_FOUND'},status_code=404)
        if isinstance(error,Conflict):
            return JSONResponse({'code':'STATE_CONFLICT'},status_code=409)
        return JSONResponse({'code':'INVALID_REQUEST'},status_code=400)

    @app.post('/v1/pair')
    async def pair(request: Request):
        try:
            token=bearer(request);value=await body(request)
            # Sync SQLite/crypto work must not block the ASGI event loop.
            from starlette.concurrency import run_in_threadpool
            return await run_in_threadpool(authority.pair,token,value)
        except (ValueError,TypeError,KeyError,Conflict,AuthorizationRequired,InvalidSignature,InvalidTag) as error:
            return failure(error)

    def dispatch(token, operation, command, value):
        if operation=='native.accept': return authority.native_accept(token,command,value)
        if operation=='native.claim': return authority.claim(token,value,mode='native')
        if operation=='execution.check': return authority.check(token,value)
        if operation=='native.retire': return authority.native_retire(token,value)
        if operation=='task.read': return authority.task(token,value)
        if operation=='cloud.create': return cloud.create(token,command,value)
        if operation=='task.control': return cloud.control(token,command,value)
        if operation=='hosting.update': return cloud.update_hosting(token,command,value)
        if operation=='source.chunk': return cloud.documents.upload(token,command,value)
        if operation=='domain.status':
            fields(value,'')
            with authority.request(token,roles=('device',)):
                return authority.domain.status()
        if operation=='worker.next': return cloud.next(token,value)
        if operation=='worker.configuration': return cloud.configuration(token,value)
        if operation=='worker.provider.prepare': return cloud.provider_prepare(token,command,value)
        if operation=='worker.provider.finish': return cloud.provider_finish(token,value)
        if operation=='worker.checkpoint': return cloud.checkpoint(token,value)
        if operation=='worker.plan': return cloud.publish_plan(token,value)
        if operation=='worker.tool': return cloud.tool(token,value)
        if operation=='worker.complete': return cloud.complete(token,value)
        if operation=='worker.evidence': return cloud.evidence(token,value)
        if operation=='worker.validation.apply': return cloud.apply_validation(token,value)
        if operation=='worker.wait': return cloud.wait(token,value)
        if operation=='artifact.read': return cloud.artifact(token,value)
        if operation=='credential.replace': return cloud.replace_credential(token,command,value)
        if operation=='device.pending': return cloud.device_pending(token,value)
        if operation=='device.confirm': return cloud.device_confirm(token,command,value)
        if operation=='device.claim': return cloud.device_claim(token,value)
        if operation=='device.finish': return cloud.device_finish(token,value)
        if operation=='device.recover': return cloud.device_recover(token,value)
        if operation=='device.abort': return cloud.device_abort(token,value)
        if operation=='device.confirm_replan': return cloud.confirm_replan(token,command,value)
        raise ValueError('unknown_operation')

    @app.post('/v1/rpc')
    async def rpc(request: Request):
        try:
            token=bearer(request);value=await body(request)
            fields(value,'protocolVersion operation commandId value')
            if type(value['protocolVersion']) is not int or value['protocolVersion']!=1:
                raise ValueError('unsupported_protocol')
            from starlette.concurrency import run_in_threadpool
            return await run_in_threadpool(dispatch,token,value['operation'],value['commandId'],value['value'])
        except (ValueError,TypeError,KeyError,Conflict,AuthorizationRequired,InvalidSignature,InvalidTag) as error:
            return failure(error)

    return app


def private_json(path, value):
    path=Path(path).absolute();_reject_reparse_traversal(path)
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w',encoding='utf-8') as stream:
        json.dump(value,stream,ensure_ascii=False,indent=2);stream.flush();os.fsync(stream.fileno())


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    init=sub.add_parser('init');init.add_argument('--root',type=Path,required=True);init.add_argument('--key-file',type=Path,required=True)
    enroll=sub.add_parser('enroll');enroll.add_argument('--root',type=Path,required=True);enroll.add_argument('--actor',required=True);enroll.add_argument('--owners',required=True);enroll.add_argument('--out',type=Path,required=True)
    revoke=sub.add_parser('revoke');revoke.add_argument('--root',type=Path,required=True);revoke.add_argument('--actor',required=True)
    retire=sub.add_parser('retire');retire.add_argument('--root',type=Path,required=True)
    serve=sub.add_parser('serve');serve.add_argument('--root',type=Path,required=True);serve.add_argument('--key-file',type=Path,required=True)
    serve.add_argument('--host',default='127.0.0.1');serve.add_argument('--port',type=int,required=True)
    serve.add_argument('--cert-file',type=Path);serve.add_argument('--tls-key-file',type=Path);serve.add_argument('--loopback-http',action='store_true')
    serve.add_argument('--worker-url',help='Explicit TLS origin used by the supervised worker, independently of the listening interface')
    serve.add_argument('--worker-ca-file',type=Path,help='CA chain for the worker authority connection; hostname verification remains enabled')
    serve.add_argument('--worker',action='store_true',help='Supervise the real model/tool executor as a separate process')
    args=parser.parse_args()
    if args.command=='init':
        args.root.mkdir(parents=True,exist_ok=False,mode=0o700)
        TaskVault.initialize(args.key_file)
        NetworkStore(args.root/'authority.sqlite3')
        print('Initialized private authority; no credentials printed.')
    elif args.command=='enroll':
        if args.out.exists(): raise FileExistsError('credential_output_exists')
        token=NetworkAuthority(NetworkStore(args.root/'authority.sqlite3')).enroll(args.actor,args.owners.split(','))
        private_json(args.out,{'actor':args.actor,'token':token,'owners':args.owners.split(',')})
        print('Enrollment written to private output file; token not printed.')
    elif args.command=='revoke':
        NetworkAuthority(NetworkStore(args.root/'authority.sqlite3')).revoke_actor(args.actor)
        print('Actor revoked; unresolved execution slots retained.')
    elif args.command=='retire':
        status=NetworkAuthority(NetworkStore(args.root/'authority.sqlite3')).domain.retire()
        print(json.dumps(status))
    else:
        local=args.host in ('127.0.0.1','::1')
        if args.loopback_http and not local:
            parser.error('HTTP is restricted to the explicit loopback development endpoint')
        if not args.loopback_http and (not args.cert_file or not args.tls_key_file):
            parser.error('TLS certificate and key are required')
        app=create_app(args.root,args.key_file,loopback_http=args.loopback_http)
        if args.worker:
            from .worker import ProcessSupervisor, Client
            connect_host='['+args.host+']' if ':' in args.host else args.host
            worker_url=args.worker_url or ('https' if args.cert_file else 'http')+'://'+connect_host+':'+str(args.port)
            # Validate before spawning any process; wildcard bind addresses are not identities.
            worker_host=urlparse(worker_url).hostname
            if worker_host in {'0.0.0.0','::'}:parser.error('--worker-url must name the certificate identity, not a wildcard bind')
            worker_ca=args.worker_ca_file or args.cert_file
            Client(worker_url,'validation-only',ca_file=worker_ca,loopback_http=args.loopback_http)
            supervisor=ProcessSupervisor(app.state.cloud,args.root,args.key_file,worker_url,
                loopback_http=args.loopback_http,ca_file=worker_ca)
            app.state.supervisor=supervisor
            from contextlib import asynccontextmanager
            @asynccontextmanager
            async def lifespan(_):
                supervisor.start()
                try: yield
                finally: supervisor.close()
            app.router.lifespan_context=lifespan
        import uvicorn
        uvicorn.run(app,host=args.host,port=args.port,proxy_headers=False,access_log=False,
                    ssl_certfile=str(args.cert_file) if args.cert_file else None,
                    ssl_keyfile=str(args.tls_key_file) if args.tls_key_file else None)


if __name__=='__main__':
    main()
