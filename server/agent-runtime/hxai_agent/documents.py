"""Trusted document-service adapter. Upload once; poll/read back, never replay unknown uploads.

The shared document-service validators remain authoritative. Its dispatcher-issued
executorStopped receipt is separate from HTTP cancellation or a terminal job state.
"""
from __future__ import annotations
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

from .authority import fields
from .hosting import grant_expired
from .provider import NoRedirect
from .runner import Tool
from .store import Lease, Conflict, UncertainEffect, AuthorizationRequired, canonical, digest, identifier

LIMIT=30*1024*1024
CHUNK=512*1024
FORMATS={'docx','xlsx','pptx','pdf','png','jpg','jpeg','webp'}
OPERATIONS={'render_office_pdf':'office_pdf','recalculate_xlsx':'xlsx_recalculate','parse_document':'parse'}
DEFINITIONS=[{'name':name,'description':description,'parameters':{'type':'object','properties':dict(
    material_id={'type':'string'},expected_sha256={'type':'string'},**({'start_page':{'type':'integer'},'end_page':{'type':'integer'}} if operation=='parse' else {})),
    'required':['material_id','expected_sha256']+(['start_page','end_page'] if operation=='parse' else []),'additionalProperties':False}}
    for name,operation,description in [
        ('render_office_pdf','office_pdf','Render an explicitly granted Office version through the original LibreOffice document service; preserves print-layout scope, not hidden slides or speaker notes.'),
        ('recalculate_xlsx','xlsx_recalculate','Actually recalculate and save an explicitly granted XLSX through LibreOffice; independently verify the saved workbook and calculation witness.'),
        ('parse_document','parse','Parse 1–5 physical pages of an explicitly granted PDF/image through the original document service; partial/failed coverage is not full success.')]]


class DocumentPending(Exception):
    pass


def validate_configuration(config, *, loopback_http=False):
    fields(config,'endpoint secret consent')
    parts=urllib.parse.urlsplit(config['endpoint'])
    if (not parts.hostname or parts.username or parts.password or parts.query or parts.fragment
            or parts.scheme!='https' and not (loopback_http and parts.scheme=='http' and parts.hostname in {'127.0.0.1','::1','localhost'})
            or config['endpoint'].endswith('/') or config['consent'] is not True
            or not isinstance(config['secret'],str) or not re.fullmatch('[A-Za-z0-9_-]{32,128}',config['secret'])):
        raise ValueError('explicit_document_service_hosting_required')


def shared_contracts():
    # Real sibling service sources are deployment inputs, never source_base or a copied verifier.
    directory=Path(__file__).resolve().parents[2]/'document-service'
    if str(directory) not in sys.path:sys.path.insert(0,str(directory))
    from api.office_output import validate_office_output
    from api.recalculation_output import validate_recalculation_output
    from api.output import validate_output
    from office_worker.check_output import check_output
    return validate_office_output,validate_recalculation_output,validate_output,check_output


def parsed_content(document):
    text='\n'.join(block['text'] for block in document['blocks'])
    if text:return text
    blank=not document['failedPages'] and set(document['blankPages'])==set(document['processedPages'])
    # A successful blank-page parse is real evidence; absent text elsewhere is NOT
    # a blank-page proof. Keep that distinction in the immutable derived receipt.
    return canonical(dict(kind='VERIFIED_BLANK_PAGE_RECEIPT' if blank else 'NO_TEXT_RETURNED_NOT_BLANK_PROOF',
        sourceSha256=document['sourceSha256'],physicalPages=document['processedPages'],blankPages=document['blankPages'],
        parserVersion=document['parserVersion'],resultRevision=document['resultRevision']))


class HostedDocuments:
    def __init__(self,host):self.host=host;self.store=host.store;self.files=host.files

    def upload(self,token,command,value):
        fields(value,'owner sourceId sha256 extension size offset bytes consent')
        identifier(value['sourceId'])
        if (value['consent'] is not True or value['extension'] not in FORMATS or not re.fullmatch('[a-f0-9]{64}',value['sha256'])
                or type(value['size']) is not int or not 1<=value['size']<=LIMIT or type(value['offset']) is not int
                or not 0<=value['offset']<value['size']):raise ValueError('invalid_source_upload')
        data=base64.b64decode(value['bytes'],validate=True)
        if not 1<=len(data)<=CHUNK or value['offset']+len(data)>value['size']:raise ValueError('source_chunk_limit')
        with self.host.authority.request(token,value['owner'],roles=('device',)) as (db,actor):
            old=self.host.authority.command(db,actor['id'],command,'source.chunk',value)
            if old is not None:return old
            self.host.authority.domain.require_active()
            upload=digest([actor['id'],value['owner'],value['sourceId'],value['sha256'],value['extension'],value['size']])
            directory=self.files.directory(value['owner'],'materials');target=directory/(upload+'.'+value['extension']);pending=directory/(upload+'.upload')
            if target.is_file():
                with target.open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
                if target.stat().st_size!=value['size'] or actual!=value['sha256']:
                    raise Conflict('immutable_source_conflict')
            else:
                # One owner cannot reserve unlimited partial uploads on this host.
                used=sum(p.stat().st_size for p in directory.iterdir() if p.is_file())
                if used+len(data)>512*1024*1024:raise ValueError('source_storage_limit')
                if pending.is_symlink():raise ValueError('unsafe_source_upload')
                with pending.open('r+b' if pending.exists() else 'w+b') as stream:
                    stream.seek(0,os.SEEK_END);length=stream.tell()
                    if length<value['offset']:raise Conflict('missing_source_chunk')
                    stream.seek(value['offset']);previous=stream.read(len(data))
                    if previous and not data.startswith(previous):raise Conflict('source_chunk_conflict')
                    if len(previous)<len(data):
                        stream.seek(value['offset']+len(previous));stream.write(data[len(previous):]);stream.flush();os.fsync(stream.fileno())
                if pending.stat().st_size==value['size']:
                    with pending.open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
                    if actual!=value['sha256']:raise Conflict('source_digest_mismatch')
                    os.link(pending,target);pending.unlink()
            return self.host.authority.acknowledge(db,actor['id'],command,dict(uploadId=upload,nextOffset=value['offset']+len(data),complete=target.is_file()))

    def source(self,actor,owner,source):
        fields(source,'id name sha256 extension size uploadId')
        identifier(source['id'])
        if not isinstance(source['name'],str) or not 1<=len(source['name'])<=512:raise ValueError('document_source_name_limit')
        if source['extension'] not in FORMATS or type(source['size']) is not int or not 1<=source['size']<=LIMIT:
            raise ValueError('invalid_hosted_document_source')
        expected=digest([actor['id'],owner,source['id'],source['sha256'],source['extension'],source['size']])
        if source['uploadId']!=expected:raise AuthorizationRequired('source_upload_not_owned')
        path=self.files.directory(owner,'materials')/(expected+'.'+source['extension'])
        with path.open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
        if path.stat().st_size!=source['size'] or actual!=source['sha256']:raise Conflict('source_version_changed')
        return dict(originalId=source['id'],name=source['name'],storageId=expected,sha256=actual,extension=source['extension'],size=source['size'])

    def config(self,row,job):
        saved=json.loads(job['snapshot_json']).get('documentService')
        if not saved:raise AuthorizationRequired('document_service_not_granted')
        binding=self.host.binding(row,dict(credential_revision=1))|{'role':'documents'}
        return self.host.vault.open(binding,saved['ciphertext'])

    def request(self,config,path,*,data=None,headers=None,limit=65536,timeout=20):
        request=urllib.request.Request(config['endpoint']+path,data=data,headers={'Authorization':'Bearer '+config['secret'],**(headers or {})})
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPSHandler(context=ssl.create_default_context()))
        try:
            with opener.open(request,timeout=timeout) as response:
                value=response.read(limit+1)
                if len(value)>limit:raise ValueError('document_response_limit')
                return value
        except urllib.error.HTTPError as error:raise Conflict('document_service_rejected_'+str(error.code)) from None

    def json(self,config,path,**kwargs):return json.loads(self.request(config,path,**kwargs))

    def registered(self):
        shared_contracts()
        return {name:Tool('documents.'+operation,'EXTERNAL',lambda args,n=name:self.validate(n,args),
            lambda lease,call,args,n=name:self.execute(n,lease,call,args),self.verify)
            for name,operation in OPERATIONS.items()}

    @staticmethod
    def validate(name,args):
        fields(args,'material_id expected_sha256'+(' start_page end_page' if name=='parse_document' else ''))
        identifier(args['material_id'])
        if not re.fullmatch('[a-f0-9]{64}',args['expected_sha256']):raise ValueError('invalid_source_hash')
        if name=='parse_document' and (type(args['start_page']) is not int or type(args['end_page']) is not int or not 1<=args['start_page']<=args['end_page']<=200 or args['end_page']-args['start_page']>=5):
            raise ValueError('document_physical_page_range')

    def execute(self,name,lease,call,args):
        operation=OPERATIONS[name]
        with self.store.transaction() as db:
            row=self.store._lease(db,lease);job=db.execute('SELECT * FROM net_jobs WHERE task_id=?',(row['id'],)).fetchone()
            snapshot=json.loads(job['snapshot_json']);config=self.config(row,job)
            source=next((s for s in snapshot['sources'] if s['id']==args['material_id'] and s.get('extension') in FORMATS),None)
            if source is None or source['sha256']!=args['expected_sha256']:raise AuthorizationRequired('document_source_not_granted')
            if source['extension'] not in ({'docx','xlsx','pptx'} if operation=='office_pdf' else {'xlsx'} if operation=='xlsx_recalculate' else {'pdf','png','jpg','jpeg','webp'}):
                raise ValueError('document_operation_format')
            journal=db.execute('SELECT * FROM net_document_calls WHERE task_id=? AND call_id=?',(row['id'],call)).fetchone()
            if journal is not None:raise DocumentPending('existing_remote_intent_requires_readback')
            key=str(uuid.uuid5(uuid.NAMESPACE_URL,canonical([row['id'],call,operation,source['sha256']])))
            db.execute("INSERT INTO net_document_calls(task_id,call_id,request_key,source_json,options_json,state) VALUES(?,?,?,?,?,'PREPARED')",
                (row['id'],call,key,canonical(source),'{}'))
        capabilities=self.json(config,'/api/v1/capabilities')
        if capabilities.get('executionReceiptVersion')!=1 or capabilities.get('requestLookupVersion')!=1:
            raise Conflict('document_service_requires_durable_stop_and_request_lookup_contract')
        if operation=='parse':
            if capabilities.get('ready') is not True or source['extension'] not in capabilities.get('formats',[]):raise Conflict('document_parser_not_ready')
            options=dict(sourceSha256=source['sha256'],extension=source['extension'],pages=[args['start_page'],args['end_page']],tier='basic',parserVersion=capabilities['parserVersion'],adapterVersion=capabilities['adapterVersion'])
            path='/api/v1/jobs?'+urllib.parse.urlencode(dict(extension=source['extension'],start_page=args['start_page'],end_page=args['end_page']))
        else:
            capability=capabilities['officePdf' if operation=='office_pdf' else 'officeRecalculation']
            if capability.get('ready') is not True:raise Conflict('document_office_not_ready')
            if not isinstance(capability.get('rendererVersion'),str) or not 1<=len(capability['rendererVersion'])<=128:
                raise ValueError('document_renderer_version_limit')
            from office_worker.contracts import RECALCULATION_OPTIONS
            options=dict(operation=operation,sourceSha256=source['sha256'],extension=source['extension'],rendererVersion=capability['rendererVersion'],
                renderOptions={'scope':'print_layout','includeHiddenSlides':False,'includeNotes':False} if operation=='office_pdf' else RECALCULATION_OPTIONS)
            path=('/api/v1/conversions' if operation=='office_pdf' else '/api/v1/recalculations')+'?extension='+source['extension']
        source_path=self.files.directory(row['owner'],'materials')/(source['storageId']+'.'+source['extension'])
        with source_path.open('rb') as stream:
            if hashlib.file_digest(stream,'sha256').hexdigest()!=source['sha256']:raise Conflict('source_version_changed')
        with self.store.transaction() as db:
            binding=db.execute('SELECT * FROM net_instances WHERE task_id=? AND fence=?',(row['id'],lease.fence)).fetchone()
            actor=db.execute('SELECT * FROM net_actors WHERE id=?',(binding['actor'],)).fetchone()
            if actor['revoked']:raise AuthorizationRequired('worker_revoked')
            self.host._worker(db,actor,dict(owner=row['owner'],taskId=row['id'],fence=lease.fence,instance=binding['instance']))
            db.execute("UPDATE net_document_calls SET options_json=?,state='UPLOADING' WHERE task_id=? AND call_id=?",(canonical(options),row['id'],call))
        result=self.json(config,path,data=source_path.read_bytes(),headers={'Content-Type':'application/octet-stream','Idempotency-Key':key,'X-Source-Sha256':source['sha256']},timeout=120)
        remote=str(uuid.UUID(result['taskId']))
        if result['operation']!=operation:raise Conflict('document_operation_changed')
        with self.store.transaction() as db:
            db.execute("UPDATE net_document_calls SET remote_id=?,state='ACKNOWLEDGED' WHERE task_id=? AND call_id=?",(remote,row['id'],call))
        raise DocumentPending('remote_job_requires_independent_readback')

    def verify(self,lease,call,args,result):
        directory=self.files.directory(lease.owner,'artifacts')/(result['artifact_id']+'.document')
        try:
            manifest=json.loads((directory/'output/manifest.json').read_text(encoding='utf-8'))
            self.validate_output(directory,result['options'])
            if result['operation']=='parse':
                content=parsed_content(json.loads((directory/'output/document.json').read_text()))
                if (directory/'output/extracted.txt').read_bytes()!=content.encode('utf-8'):return False
            return manifest['sourceSha256']==args['expected_sha256'] and digest(manifest)==result['manifest_sha256'] and self.artifact_bytes(lease.owner,result)['sha256']==result['sha256']
        except (OSError,ValueError,KeyError):return False

    @staticmethod
    def validate_output(directory,options):
        office,recalculate,parse,check=shared_contracts()
        operation=options.get('operation','parse')
        if operation=='office_pdf':check(directory/'output');return office(directory,options,publish=False)
        if operation=='xlsx_recalculate':check(directory/'output');return recalculate(directory,options,publish=False)
        return parse(directory,options,publish=False)

    def artifact_bytes(self,owner,result,*,offset=0,limit=CHUNK):
        path=self.files.directory(owner,'artifacts')/(result['artifact_id']+'.document')/'output'/result['relative_path']
        if path.is_symlink():raise ValueError('unsafe_document_artifact')
        with path.open('rb') as stream:
            actual=hashlib.file_digest(stream,'sha256').hexdigest()
            if actual!=result['sha256'] or path.stat().st_size!=result['size']:raise Conflict('document_artifact_changed')
            stream.seek(offset);data=stream.read(limit)
        return dict(artifactId=result['artifact_id'],sha256=actual,extension=result['extension'],bytes=base64.b64encode(data).decode(),
            parentId=result['source']['originalId'],sourceSha256=result['source']['sha256'],offset=offset,size=result['size'],nextOffset=offset+len(data))

    def download(self,row,journal,config,remote):
        source=json.loads(journal['source_json']);options=json.loads(journal['options_json'])
        artifact=digest([row['id'],journal['call_id'],'document-version'])
        directory=self.files.directory(row['owner'],'artifacts')/(artifact+'.document');output=directory/'output'
        output.mkdir(parents=True,exist_ok=True)
        manifest=self.json(config,'/api/v1/jobs/'+remote+'/manifest',limit=2*1024*1024)
        if manifest['sourceSha256']!=source['sha256']:raise Conflict('document_manifest_source_changed')
        assets=manifest.get('assets')
        if not isinstance(assets,list) or not 1<=len(assets)<=2000:raise ValueError('document_asset_count')
        total=0
        for asset in assets:
            if not re.fullmatch('[a-z0-9-]{1,64}',asset['assetId']) or not re.fullmatch('[a-z0-9.-]{1,100}',asset['path']) or '..' in asset['path']:
                raise ValueError('unsafe_document_asset')
            if type(asset['size']) is not int or not 1<=asset['size']<=64*1024*1024:raise ValueError('document_asset_limit')
            total+=asset['size']
            if total>64*1024*1024:raise ValueError('document_output_limit')
            data=self.request(config,'/api/v1/jobs/'+remote+'/assets/'+asset['assetId'],limit=asset['size'],timeout=60)
            if len(data)!=asset['size'] or hashlib.sha256(data).hexdigest()!=asset['sha256']:raise Conflict('document_download_digest')
            path=output/asset['path']
            if path.is_symlink():raise ValueError('unsafe_document_output')
            if path.exists():
                if path.read_bytes()!=data:raise Conflict('immutable_document_output_changed')
            else:
                with path.open('xb') as stream:stream.write(data);stream.flush();os.fsync(stream.fileno())
        manifest_path=output/'manifest.json'
        if manifest_path.exists() and json.loads(manifest_path.read_text())!=manifest:raise Conflict('immutable_manifest_changed')
        if not manifest_path.exists():manifest_path.write_text(canonical(manifest),encoding='utf-8')
        self.validate_output(directory,options)
        operation=options.get('operation','parse')
        if operation=='office_pdf':
            from pypdf import PdfReader
            content='\n'.join(p.extract_text() for p in PdfReader(output/'output.pdf',strict=True).pages);extension='pdf';primary=assets[0]
        elif operation=='xlsx_recalculate':
            witness=json.loads((output/'calculation.json').read_text());content=canonical({'sheetNames':witness['sheetNames'],'cells':witness['cells']});extension='xlsx';primary=next(a for a in assets if a['assetId']=='xlsx')
        else:
            if manifest['failedPages']:raise Conflict('document_partial_coverage_not_goal_success')
            parsed=json.loads((output/'document.json').read_text());content=parsed_content(parsed);extension='txt'
            data=content.encode('utf-8');derived=output/'extracted.txt'
            if derived.exists():
                if derived.read_bytes()!=data:raise Conflict('immutable_extraction_changed')
            else:
                with derived.open('xb') as stream:stream.write(data);stream.flush();os.fsync(stream.fileno())
            primary=dict(sha256=hashlib.sha256(data).hexdigest(),size=len(data),path='extracted.txt')
        encoded=content.encode('utf-8');projection=encoded[:8000].decode('utf-8',errors='ignore')
        warning_bytes='\n'.join(manifest['warnings']).encode('utf-8')
        result=dict(artifact_id=artifact,sha256=primary['sha256'],size=primary['size'],relative_path=primary['path'],extension=extension,
            source=source,options=options,manifest_sha256=digest(manifest),content=projection,textTruncated=len(encoded)>8000,
            warningsSummary=warning_bytes[:1024].decode('utf-8',errors='ignore'),warningsTruncated=len(warning_bytes)>1024,
            operation=operation,remoteTaskId=remote,verifier='ORIGINAL_DOCUMENT_SERVICE_INDEPENDENT_READBACK')
        return result

    def settle_pending(self):
        with self.store.transaction() as db:
            pending=[dict(r) for r in db.execute("""SELECT d.*,t.owner FROM net_document_calls d JOIN tasks t ON t.id=d.task_id
                JOIN calls c ON c.task_id=d.task_id AND c.call_id=d.call_id WHERE c.state='UNKNOWN'
                AND NOT EXISTS(SELECT 1 FROM net_instances i WHERE i.task_id=d.task_id AND i.state!='STOPPED')""")]
        for journal in pending:
            try:self.settle(journal)
            except (OSError,ValueError,KeyError,Conflict,AuthorizationRequired,urllib.error.URLError):
                # Readback/stop unavailable: retain UNKNOWN and the unique execution slot.
                continue

    def settle(self,journal):
        with self.store.transaction() as db:
            row=self.store._owned(db,journal['owner'],journal['task_id']);job=db.execute('SELECT * FROM net_jobs WHERE task_id=?',(row['id'],)).fetchone()
            config=self.config(row,job);snapshot=json.loads(job['snapshot_json'])
            source_actor=db.execute('SELECT revoked FROM net_actors WHERE id=?',(job['actor'],)).fetchone()
            withdrawn=bool(row['cancel_requested'] or row['manual_pause'] or row['authorization']!='ACTIVE' or job['credential_revoked'] or not source_actor or source_actor['revoked'] or grant_expired(snapshot['grant']))
        remote=journal['remote_id']
        if journal['state']=='PREPARED':
            return self.settle_failed(row,journal,'DOCUMENT_NOT_DISPATCHED')
        if remote is None:
            found=self.json(config,'/api/v1/requests/'+journal['request_key'])['job']
            if found is None:
                endpoint='parse-requests' if not json.loads(journal['options_json']).get('operation') else 'conversion-requests' if json.loads(journal['options_json'])['operation']=='office_pdf' else 'recalculation-requests'
                self.json(config,'/api/v1/'+endpoint+'/'+journal['request_key']+'/cancel',data=b'')
                return  # A tombstone ACK is not proof that a slow original upload ended.
            if found['intent']!=json.loads(journal['options_json']):raise Conflict('remote_request_intent_changed')
            remote=str(uuid.UUID(found['taskId']))
            with self.store.transaction() as db:db.execute('UPDATE net_document_calls SET remote_id=? WHERE task_id=? AND call_id=?',(remote,row['id'],journal['call_id']))
        status=self.json(config,'/api/v1/jobs/'+remote)
        if withdrawn and status['state'] in {'QUEUED','RUNNING'}:
            status=self.json(config,'/api/v1/jobs/'+remote+'/cancel',data=b'')
        if status.get('executorStopped') is not True:return
        if status['state'] not in {'SUCCEEDED','PARTIAL','FAILED','CANCELLED','EXPIRED'}:return
        if status['state']!='SUCCEEDED':return self.settle_failed(row,journal,'DOCUMENT_'+status['state'])
        result=self.download(row,journal,config,remote)
        with self.store.transaction() as db:
            if db.execute("SELECT 1 FROM net_instances WHERE task_id=? AND state!='STOPPED'",(row['id'],)).fetchone():raise UncertainEffect('old_executor_not_stopped')
            self.store.reconcile_applied(row['owner'],row['id'],journal['call_id'],result,
                dict(verified=True,method='ORIGINAL_DOCUMENT_SERVICE_INDEPENDENT_READBACK',result_sha256=digest(result)),executor_stopped=True)
            db.execute("UPDATE net_document_calls SET state='VERIFIED',result_json=? WHERE task_id=? AND call_id=?",(canonical(result),row['id'],journal['call_id']))
            current=self.store._owned(db,row['owner'],row['id'])
            # Known ACK + independent result may continue the accepted async task. Lost ACK
            # or any blocker retains explicit-resume semantics; neither permits re-upload.
            if journal['state']=='ACKNOWLEDGED' and not withdrawn and not self.store._stop_state(current):
                self.store.resume(row['owner'],row['id'],current['revision'])

    def settle_failed(self,row,journal,reason):
        with self.store.transaction() as db:
            if db.execute("SELECT 1 FROM net_instances WHERE task_id=? AND state!='STOPPED'",(row['id'],)).fetchone():raise UncertainEffect('old_executor_not_stopped')
            db.execute("UPDATE calls SET state='FAILED_SAFE',reason=? WHERE task_id=? AND call_id=? AND state='UNKNOWN'",(reason,row['id'],journal['call_id']))
            db.execute("UPDATE net_document_calls SET state='SETTLED_FAILED' WHERE task_id=? AND call_id=?",(row['id'],journal['call_id']))
            if not self.store._unsafe(db,row['id']):
                current=self.store._owned(db,row['owner'],row['id'])
                db.execute('UPDATE tasks SET state=?,reason=?,revision=revision+1 WHERE id=?',(self.store._stop_state(current) or 'FAILED_BLOCKED',reason,row['id']))
                self.store._release(db,row['id'])
