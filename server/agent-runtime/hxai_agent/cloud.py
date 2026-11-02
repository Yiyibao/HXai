"""Cloud host adapters for the original TaskStore/Runner and V6 plan/acceptance protocol.

The worker proposes plans and calls; this host owns grants, classifications, output
readback, journals and completion. Native-only tools are deferred to the paired App,
not replaced with a text-only simulation. No untrusted caller can submit a verifier.
"""
from __future__ import annotations
import base64
import json
import re
import time
from dataclasses import asdict
from pathlib import Path
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import padding
from .authority import fields, raw_sha
from .provider import validate_configuration, encode_request
from .runner import LocalFiles, Runner, registry
from .hosting import validate_grant, grant_expired
from .documents import HostedDocuments, DocumentPending, DEFINITIONS as DOCUMENT_DEFINITIONS, OPERATIONS as DOCUMENT_TOOLS, validate_configuration as validate_document_configuration
from .store import (ModelLock, Lease, Permit, Receipt, Conflict, UncertainEffect,
                    AuthorizationRequired, TaskStore, canonical, digest, identifier, TERMINAL)

DEVICE_TOOLS={'create_material_file','edit_material_file','read_material_file','math_verify',
              'device_screen_action','capture_device_screen'}
LOCAL_DEFINITIONS=[
 {'name':'read_material_text','description':'Read an explicitly uploaded UTF-8 source; returns exact source hash and complete bounded text.',
  'parameters':{'type':'object','properties':{'material_id':{'type':'string'}},'required':['material_id'],'additionalProperties':False}},
 {'name':'write_report_version','description':'Write a new immutable Markdown version; never an Office document or a sent message.',
  'parameters':{'type':'object','properties':{'family':{'type':'string'},'content':{'type':'string'},'parent_artifact_id':{'type':['string','null']}},'required':['family','content','parent_artifact_id'],'additionalProperties':False}},
 {'name':'verify_equal_expression_chain','description':'Exact Q[x] equal-expression chain only; UNSUPPORTED/DISPROVED do not prove the user goal.',
  'parameters':{'type':'object','properties':{'expressions':{'type':'array','items':{'type':'string'}}},'required':['expressions'],'additionalProperties':False}},
]


class CloudHost:
    def __init__(self, authority, vault, files_root, *, loopback_http=False):
        self.authority=authority;self.store=authority.store;self.vault=vault
        self.files=LocalFiles(Path(files_root));self.tools=registry(self.files)
        self.documents=HostedDocuments(self);self.tools.update(self.documents.registered())
        self.runner=Runner(self.store,self.tools);self.loopback_http=loopback_http

    @staticmethod
    def binding(row, job):
        return {'owner':row['owner'],'taskId':row['id'],'model':json.loads(row['model_json']),
                'credentialRevision':job['credential_revision']}

    @staticmethod
    def base(value):
        return {k:value[k] for k in ('owner','taskId','fence','instance')}

    def _worker(self, db, actor, value, *, dispatch=True):
        row=self.authority._bound(db,actor,value['owner'],value['taskId'],value['fence'],value['instance'],dispatch=dispatch)
        job=db.execute('SELECT * FROM net_jobs WHERE task_id=?',(row['id'],)).fetchone()
        if job is None or job['mode']!='cloud' or actor['role']!='worker':
            raise AuthorizationRequired('worker_scope_denied')
        source=db.execute('SELECT revoked FROM net_actors WHERE id=?',(job['actor'],)).fetchone()
        if dispatch and (source is None or source['revoked'] or job['credential_revoked']):
            raise AuthorizationRequired('hosting_grant_revoked')
        snapshot=json.loads(job['snapshot_json'])
        if dispatch and grant_expired(snapshot['grant']):
            raise AuthorizationRequired('hosting_grant_expired')
        target=snapshot.get('validation')
        if dispatch and target:
            original=self.store._owned(db,row['owner'],target['taskId'])
            old=db.execute('SELECT * FROM net_jobs WHERE task_id=?',(original['id'],)).fetchone()
            if (original['authorization']!=target.get('authorization','EXPIRED') or original['cancel_requested']
                    or original['auth_epoch']!=target['grantEpoch'] or old['credential_revoked']
                    or old['credential_revision']!=target['credentialRevision']
                    or original['model_json']!=row['model_json']):
                raise AuthorizationRequired('credential_validation_scope_changed')
        if dispatch and (job['input_blocked'] or job['device_blocked'] or job['device_confirmation']):
            raise AuthorizationRequired('independent_blocker')
        return row,job

    def _owned_job(self, db, actor, owner, task):
        row=self.store._owned(db,owner,task)
        job=db.execute('SELECT * FROM net_jobs WHERE task_id=?',(task,)).fetchone()
        if job is None or job['actor']!=actor['id'] or job['mode']!='cloud':
            raise AuthorizationRequired('job_not_owned')
        return row,job

    def create(self, token, command_id, value):
        optional=''.join(' '+k for k in ('nativeHistory','initialControl','context','documentService') if k in value)
        fields(value,'owner sourceId goal model configuration tools requireEvidence grant sources'+optional)
        owner=value['owner'];identifier(value['sourceId']);lock=ModelLock(**value['model']);lock.encoded()
        validate_configuration(value['configuration'],loopback_http=self.loopback_http)
        validate_grant(value['grant'])
        document_config=value.get('documentService')
        if document_config is not None:validate_document_configuration(document_config,loopback_http=self.loopback_http)
        if type(value['requireEvidence']) is not bool or not isinstance(value['tools'],list) or len(value['tools'])>len(DEVICE_TOOLS):
            raise ValueError('invalid_hosting_contract')
        names=set()
        for tool in value['tools']:
            fields(tool,'name description parameters')
            if tool['name'] not in DEVICE_TOOLS or tool['name'] in names or not isinstance(tool['parameters'],dict):
                raise ValueError('device_tool_not_registered')
            if not isinstance(tool['description'],str) or len(tool['description'])>16000:
                raise ValueError('device_tool_description_limit')
            names.add(tool['name'])
        sources=value['sources']
        if not isinstance(sources,list) or len(sources)>16:raise ValueError('source_limit')
        for source in sources:
            LocalFiles.material_id(source['id'])
            if 'text' in source:
                fields(source,'id text sha256')
                if not isinstance(source['text'],str) or len(source['text'].encode())>16000 or raw_sha(source['text'])!=source['sha256']:
                    raise ValueError('source_digest_mismatch')
            elif document_config is None:raise AuthorizationRequired('binary_sources_require_document_hosting_consent')
            else:fields(source,'id name sha256 extension size uploadId')
        with self.authority.request(token,owner,roles=('device',)) as (db,actor):
            old=self.authority.command(db,actor['id'],command_id,'cloud.create',value)
            if old is not None:return old
            self.authority.domain.require_active()
            # A lost ACK remains recoverable after grant expiry; this is not fresh permission.
            validate_grant(value['grant'],fresh=True)
            history=value.get('nativeHistory')
            if history is not None:
                fields(history,'owner sourceId journalHash steps signature')
                if history['owner']!=owner or history['sourceId']!=value['sourceId'] or not re.fullmatch('[0-9a-f]{64}',history['journalHash']):
                    raise ValueError('native_checkpoint_binding')
                self._signature(actor,{k:v for k,v in history.items() if k!='signature'},history['signature'])
                if not isinstance(history['steps'],list) or len(history['steps'])>64:
                    raise ValueError('native_checkpoint_limit')
                seen=set()
                for step in history['steps']:
                    fields(step,'id tool arguments result')
                    identifier(step['id'])
                    if step['id'] in seen:raise ValueError('duplicate_native_checkpoint')
                    seen.add(step['id'])
                    result=step['result'];fields(result,'tool inputHash nativeTaskId nativeFence output artifacts verifier')
                    if result['tool']!=step['tool'] or result['inputHash']!=digest(step['arguments']) or result['nativeTaskId']!=value['sourceId'] or result['verifier']!='ORIGINAL_NATIVE_VERIFIER':
                        raise ValueError('native_checkpoint_intent')
            initial=value.get('initialControl',dict(manualPause=False,inputBlocked=False,deviceBlocked=False,deviceConfirmation=False))
            fields(initial,'manualPause inputBlocked deviceBlocked deviceConfirmation')
            if any(type(v) is not bool for v in initial.values()):raise ValueError('invalid_initial_control')
            context=value.get('context')
            if context is not None:
                fields(context,'sourceHash instructions messages coverage')
                if (not isinstance(context['sourceHash'],str) or not re.fullmatch('[0-9a-f]{64}',context['sourceHash'])
                        or not isinstance(context['instructions'],str) or not isinstance(context['messages'],list)
                        or len(context['messages'])>400 or len(canonical(context).encode())>100000
                        or context['coverage']!='SOURCE_PROJECTED_TEXT_ONLY_NO_IMAGE_BYTES_OR_AUTOMATIC_MEMORY_EXPORT'):
                    raise ValueError('invalid_native_context')
                for message in context['messages']:
                    fields(message,'role content')
                    if message['role'] not in {'user','assistant'} or not isinstance(message['content'],str):
                        raise ValueError('invalid_native_context_message')
            task=self.store.enqueue(owner,'cloud:'+digest([actor['id'],value['sourceId']]),value['goal'],lock,[],
                [t.capability for name,t in self.tools.items() if document_config is not None or name not in DOCUMENT_TOOLS]+['device.'+n for n in names],{},require_evidence=False)
            # Sources are immutable, task-scoped names. A lost ACK is read back, never overwritten.
            source_records=[]
            for source in sources:
                source_id=digest([task,source['id']])
                if 'text' not in source:
                    selected=self.documents.source(actor,owner,source)
                    source_records.append(dict(selected,id=source_id));continue
                path=self.files.directory(owner,'materials')/(source_id+'.txt')
                if path.exists():
                    if raw_sha(path.read_text(encoding='utf-8'))!=source['sha256']:raise Conflict('source_version_conflict')
                else:self.files.import_synthetic_text(owner,source_id,source['text'])
                source_records.append({'id':source_id,'originalId':source['id'],'sha256':source['sha256']})
            snapshot={k:v for k,v in value.items() if k not in {'configuration','sources','documentService'}}
            snapshot['sources']=source_records
            snapshot['provider']={k:value['configuration'][k] for k in ('protocol','endpoint','wireModel')}
            snapshot['configurationHash']=digest(value['configuration'])
            row=self.store.get(owner,task)
            job={'credential_revision':1}
            cipher=self.vault.seal(self.binding(row,job),value['configuration'])
            if document_config is not None:
                snapshot['documentService']={'endpoint':document_config['endpoint'],'configurationHash':digest(document_config),
                    'ciphertext':self.vault.seal(self.binding(row,job)|{'role':'documents'},document_config)}
            db.execute("INSERT INTO net_jobs(task_id,mode,source_id,actor,snapshot_json,config_cipher) VALUES(?,'cloud',?,?,?,?)",
                       (task,value['sourceId'],actor['id'],canonical(snapshot),cipher))
            if history:
                for step in history['steps']:
                    result=step['result'];proof={'verified':True,'method':'ORIGINAL_NATIVE_VERIFIER','result_sha256':digest(result)}
                    db.execute("INSERT INTO calls(task_id,call_id,tool,capability,effect,arguments_hash,arguments_json,state,fence,auth_epoch,result_json,verification_json,created,updated) VALUES(?,?,?,?,?,?,?,'SUCCEEDED',0,1,?,?,?,?)",
                        (task,step['id'],step['tool'],'native.retained','READ',digest(step['arguments']),canonical(step['arguments']),canonical(result),canonical(proof),time.time(),time.time()))
                    db.execute("INSERT INTO net_device_calls(task_id,id,tool,input_json,input_hash,grant_epoch,state,device_actor,result_json,result_hash) VALUES(?,?,?,?,?,1,'SUCCEEDED',?,?,?)",
                        (task,step['id'],step['tool'],canonical(step['arguments']),digest(step['arguments']),actor['id'],canonical(result),digest(result)))
            blocked='WAITING_INPUT' if initial['inputBlocked'] else 'WAITING_DEVICE' if initial['deviceBlocked'] or initial['deviceConfirmation'] else None
            state='PAUSED_BY_USER' if initial['manualPause'] else blocked or 'QUEUED'
            db.execute('UPDATE tasks SET state=?,manual_pause=?,blocked_on=?,device_confirmation=? WHERE id=?',
                       (state,int(initial['manualPause']),blocked,int(initial['deviceConfirmation']),task))
            db.execute('UPDATE net_jobs SET input_blocked=?,device_blocked=?,device_confirmation=? WHERE task_id=?',
                       (int(initial['inputBlocked']),int(initial['deviceBlocked']),int(initial['deviceConfirmation']),task))
            return self.authority.acknowledge(db,actor['id'],command_id,{'taskId':task,'sourceId':value['sourceId'],'owner':owner,'state':state,'model':value['model']})

    def next(self, token, value):
        fields(value,'instance')
        with self.authority.request(token,roles=('worker',)) as (db,actor):
            scope=self.authority.worker_scope(actor)
            if scope[1]!=value['instance']:raise AuthorizationRequired('worker_generation_scope_denied')
            process=db.execute("SELECT 1 FROM net_processes WHERE instance=? AND actor=? AND state='RUNNING'",(value['instance'],actor['id'])).fetchone()
            self.authority.domain.require_active()
            if process is None:raise AuthorizationRequired('unregistered_host_process')
            if db.execute('SELECT task_id FROM execution_slot WHERE singleton=1').fetchone()[0] is not None:return {'job':None}
            first=db.execute("SELECT t.* FROM tasks t WHERE state='QUEUED' AND authorization='ACTIVE' AND manual_pause=0 AND cancel_requested=0 AND blocked_on IS NULL AND device_confirmation=0 ORDER BY priority DESC,queue_order,sequence LIMIT 1").fetchone()
            if first is None or first['owner'] not in json.loads(actor['owners_json']):return {'job':None}
            if scope[0]!=first['id']:return {'job':None}
            job=db.execute("SELECT * FROM net_jobs WHERE task_id=? AND mode='cloud'",(first['id'],)).fetchone()
            if job is None or db.execute("SELECT 1 FROM net_device_calls WHERE task_id=? AND state IN ('READY','IN_FLIGHT')",(first['id'],)).fetchone():return {'job':None}
            lease=self.authority.claim(token,{'owner':first['owner'],'taskId':first['id'],'instance':value['instance']},mode='cloud')
            if lease['state']!='RUNNING':return {'job':None}
            return {'job':dict(lease,owner=first['owner'])}

    def configuration(self, token, value):
        fields(value,'owner taskId fence instance')
        with self.authority.request(token,value['owner'],roles=('worker',)) as (db,actor):
            row,job=self._worker(db,actor,value)
            snapshot=json.loads(job['snapshot_json'])
            saved=db.execute('SELECT value_json FROM net_checkpoints WHERE task_id=?',(row['id'],)).fetchone()
            return {'binding':self.binding(row,job),'ciphertext':job['config_cipher'],'snapshot':snapshot,
                    'goal':row['goal'],'goalRevision':job['goal_revision'],'plannedRevision':job['planned_revision'],
                    'plan':json.loads(job['plan_json']) if job['plan_json'] else None,
                    'checkpoint':json.loads(saved[0]) if saved else None,
                    'tools':LOCAL_DEFINITIONS+(DOCUMENT_DEFINITIONS if snapshot.get('documentService') else [])+snapshot['tools']}

    def provider_prepare(self, token, command_id, value):
        fields(value,'owner taskId fence instance role body')
        if value['role'] not in {'planning','main','semantic_acceptance','credential_validation'}:
            raise ValueError('invalid_provider_role')
        if not isinstance(value['body'],dict) or len(canonical(value['body']).encode())>262144:raise ValueError('provider_context_limit')
        with self.authority.request(token,value['owner'],roles=('worker',)) as (db,actor):
            row,job=self._worker(db,actor,value)
            config=self.vault.open(self.binding(row,job),job['config_cipher'])
            if config['protocol']!='google' and value['body'].get('model')!=config['wireModel']:
                raise Conflict('locked_wire_model_changed')
            if any(value['body'].get(k)!=v for k,v in config['baseBody'].items()):
                raise Conflict('locked_request_configuration_changed')
            intent=digest([value['role'],value['body'],job['credential_revision']])
            prior=db.execute('SELECT * FROM net_provider_calls WHERE task_id=? AND id=?',(row['id'],command_id)).fetchone()
            if prior:
                if prior['input_hash']!=intent:raise Conflict('physical_request_identity_changed')
                if prior['state']=='RETURNED':return {'requestId':command_id,'dispatch':False,'result':json.loads(prior['result_json'])}
                raise UncertainEffect('physical_request_outcome_unknown_do_not_replay')
            if self.store._unsafe(db,row['id']):raise UncertainEffect('unresolved_operation')
            db.execute("INSERT INTO net_provider_calls(task_id,id,fence,instance,input_hash,body_json,role,state,result_json) VALUES(?,?,?,?,?,?,?,'IN_FLIGHT',?)",
                (row['id'],command_id,value['fence'],value['instance'],intent,canonical(value['body']),value['role'],
                 canonical({'metadata':{'configurationHash':digest(config),'credentialRevision':job['credential_revision']}})))
            self.store.record_usage(row['owner'],row['id'],command_id,'unknown')
            return {'requestId':command_id,'dispatch':True,'credentialRevision':job['credential_revision']}

    def provider_finish(self, token, value):
        fields(value,'owner taskId fence instance requestId result rejectedStatus')
        with self.authority.request(token,value['owner'],roles=('worker',),settlement=True) as (db,actor):
            row,job=self._worker(db,actor,value,dispatch=False)
            prior=db.execute('SELECT * FROM net_provider_calls WHERE task_id=? AND id=?',(row['id'],value['requestId'])).fetchone()
            if prior is None or prior['fence']!=value['fence'] or prior['instance']!=value['instance']:
                raise Conflict('physical_receipt_not_bound')
            if len(canonical(value['result']).encode())>1048576:raise ValueError('provider_result_limit')
            observed=json.loads(prior['result_json']) if prior['result_json'] else {}
            metadata=observed.get('metadata',{})
            payload=canonical({'result':value['result'],'rejectedStatus':value['rejectedStatus'],'metadata':metadata})
            if prior['state']=='RETURNED':
                if observed.get('result')!=value['result'] or observed.get('rejectedStatus')!=value['rejectedStatus']:
                    raise Conflict('physical_receipt_changed')
                return {'recorded':True}
            if prior['state'] not in {'IN_FLIGHT','UNKNOWN'}:raise Conflict('physical_receipt_not_pending')
            result=value['result'];usage=None if result is None else result.get('usage')
            if usage is not None:
                fields(usage,'input output cached')
                self.store.record_usage(row['owner'],row['id'],value['requestId'],'provider_reported',usage['input'],usage['output'],usage['cached'])
            db.execute("UPDATE net_provider_calls SET state='RETURNED',result_json=? WHERE task_id=? AND id=?",(payload,row['id'],value['requestId']))
            if value['rejectedStatus'] is not None and json.loads(job['snapshot_json']).get('validation'):
                if row['state']=='RUNNING' and not self.store._stop_state(row):
                    self.store.complete(Lease(row['id'],row['owner'],row['fence']),
                        {'validation':'REJECTED','httpStatus':value['rejectedStatus']})
            elif value['rejectedStatus'] in (401,403):
                self.store.change_authorization(row['owner'],row['id'],'EXPIRED' if value['rejectedStatus']==401 else 'REVOKED')
            return {'recorded':True}

    def checkpoint(self, token, value):
        fields(value,'owner taskId fence instance checkpoint')
        if not isinstance(value['checkpoint'],dict) or len(canonical(value['checkpoint']).encode())>524288:raise ValueError('checkpoint_limit')
        with self.authority.request(token,value['owner'],roles=('worker',)) as (db,actor):
            row,job=self._worker(db,actor,value)
            if value['checkpoint'].get('goalRevision')!=job['goal_revision']:raise Conflict('stale_goal_checkpoint')
            db.execute('INSERT INTO net_checkpoints(task_id,value_json) VALUES(?,?) ON CONFLICT(task_id) DO UPDATE SET value_json=excluded.value_json',
                       (row['id'],canonical(value['checkpoint'])))
            return {'recorded':True}

    def publish_plan(self, token, value):
        fields(value,'owner taskId fence instance goalRevision plan')
        plan=value['plan'];fields(plan,'steps acceptance explanation')
        if not isinstance(plan['steps'],list) or not isinstance(plan['acceptance'],list) or len(plan['steps'])>64 or len(plan['acceptance'])>64:
            raise ValueError('plan_limit')
        with self.authority.request(token,value['owner'],roles=('worker',)) as (db,actor):
            row,job=self._worker(db,actor,value)
            if value['goalRevision']!=job['goal_revision']:raise Conflict('stale_goal_plan')
            snapshot=json.loads(job['snapshot_json']);available=set(self.tools)|{t['name'] for t in snapshot['tools']}
            available.update(r[0] for r in db.execute("SELECT tool FROM calls WHERE task_id=? AND state='SUCCEEDED'",(row['id'],)))
            ids=set();by_id={}
            for step in plan['steps']:
                fields(step,'id tool purpose')
                if not isinstance(step['id'],str) or not re.fullmatch('[A-Za-z0-9_-]{1,80}',step['id']) or step['id'] in ids or step['tool'] not in available:
                    raise ValueError('invalid_plan_step')
                if not isinstance(step['purpose'],str) or not 1<=len(step['purpose'])<=2000:raise ValueError('invalid_plan_purpose')
                ids.add(step['id']);by_id[step['id']]=step
            done=db.execute("SELECT call_id,tool FROM calls WHERE task_id=? AND state='SUCCEEDED'",(row['id'],)).fetchall()
            if any(r['call_id'] not in ids or by_id[r['call_id']]['tool']!=r['tool'] for r in done):
                raise Conflict('verified_steps_must_be_retained')
            if snapshot['requireEvidence'] and (not ids or not plan['acceptance']):raise ValueError('action_goal_requires_plan_and_predicates')
            for rule in plan['acceptance']:
                if not isinstance(rule,dict) or set(rule)-{'stepId','kind','format','contains','sourceId','sourceHash'} or rule.get('stepId') not in ids:
                    raise ValueError('invalid_acceptance_predicate')
                if rule.get('kind') not in {'artifact_content','tool_verified'}:raise ValueError('unsupported_acceptance_predicate')
                if rule['kind']=='artifact_content' and (rule.get('format') not in {'md','txt','docx','xlsx','pptx','pdf'} or not rule.get('contains')):
                    raise ValueError('artifact_predicate_required')
                if not isinstance(rule.get('contains',[]),list) or any(not isinstance(t,str) or not 1<=len(t)<=4000 for t in rule.get('contains',[])):
                    raise ValueError('invalid_acceptance_content')
            db.execute('UPDATE tasks SET plan_json=?,acceptance_json=?,require_evidence=? WHERE id=?',
                       (canonical([s['id'] for s in plan['steps']]),'{}',int(snapshot['requireEvidence']),row['id']))
            db.execute('UPDATE net_jobs SET planned_revision=goal_revision,plan_json=? WHERE task_id=?',(canonical(plan),row['id']))
            return {'published':True,'goalRevision':job['goal_revision']}

    def tool(self, token, value):
        fields(value,'owner taskId fence instance callId tool arguments')
        with self.authority.request(token,value['owner'],roles=('worker',)) as (db,actor):
            row,job=self._worker(db,actor,value)
            plan=json.loads(job['plan_json']) if job['plan_json'] else None
            if plan is None or job['planned_revision']!=job['goal_revision']:raise Conflict('plan_required')
            step=next((s for s in plan['steps'] if s['id']==value['callId']),None)
            if step is None or step['tool']!=value['tool']:raise Conflict('call_not_in_plan')
            if value['tool']=='read_material_text':
                self.tools['read_material_text'].validate(value['arguments'])
                selected=json.loads(job['snapshot_json'])['sources']
                if not any(s['id']==value['arguments']['material_id'] and 'storageId' not in s for s in selected):
                    raise AuthorizationRequired('source_not_selected_for_this_task')
            if value['tool'] not in self.tools:
                device=next((t for t in json.loads(job['snapshot_json'])['tools'] if t['name']==value['tool']),None)
                if device is None:raise AuthorizationRequired('tool_not_granted')
                old=db.execute('SELECT * FROM net_device_calls WHERE task_id=? AND id=?',(row['id'],value['callId'])).fetchone()
                encoded=canonical(value['arguments']);input_hash=raw_sha(encoded)
                if old:
                    if old['tool']!=value['tool'] or old['input_hash']!=input_hash:raise Conflict('device_intent_changed')
                    if old['state']=='SUCCEEDED':return {'state':'VERIFIED','result':json.loads(old['result_json'])}
                    if old['state'] in ('IN_FLIGHT','UNKNOWN'):raise UncertainEffect('device_outcome_unknown')
                else:
                    db.execute("INSERT INTO net_device_calls(task_id,id,tool,input_json,input_hash,grant_epoch,state) VALUES(?,?,?,?,?,?,'WAITING')",
                               (row['id'],value['callId'],value['tool'],encoded,input_hash,row['auth_epoch']))
                db.execute('UPDATE net_jobs SET device_blocked=1,device_confirmation=1 WHERE task_id=?',(row['id'],))
                self.store.wait(Lease(row['id'],row['owner'],row['fence']),'WAITING_DEVICE','paired_device_action',foreground_required=True)
                return {'state':'WAITING_DEVICE','callId':value['callId']}
        # Tool policy and independent verifier come only from the original host registry.
        try:receipt=self.runner.call(Lease(value['taskId'],value['owner'],value['fence']),value['callId'],value['tool'],value['arguments'])
        except DocumentPending:return {'state':'WAITING_DOCUMENT','callId':value['callId']}
        return {'state':'VERIFIED','result':receipt.result,'verification':receipt.verification}

    def _evidence(self, db, row, job):
        if job['planned_revision']!=job['goal_revision'] or not job['plan_json']:raise Conflict('plan_not_current')
        plan=json.loads(job['plan_json']);evidence=[];by_id={}
        for step in plan['steps']:
            call=db.execute("SELECT * FROM calls WHERE task_id=? AND call_id=? AND state='SUCCEEDED'",(row['id'],step['id'])).fetchone()
            if call is None or call['tool']!=step['tool']:raise Conflict('planned_receipt_missing')
            result=json.loads(call['result_json']);proof=json.loads(call['verification_json'])
            if proof.get('result_sha256')!=digest(result):raise Conflict('receipt_integrity_mismatch')
            args=json.loads(call['arguments_json'])
            if step['tool'] in self.tools:
                tool=self.tools[step['tool']]
                if not tool.verify(Lease(row['id'],row['owner'],call['fence']),step['id'],args,result):raise Conflict('independent_readback_failed')
            else:
                signed=db.execute("SELECT * FROM net_device_calls WHERE task_id=? AND id=? AND state='SUCCEEDED'",(row['id'],step['id'])).fetchone()
                if signed is None or signed['result_hash']!=digest(result):raise Conflict('native_receipt_missing')
            if result.get('status') in {'UNSUPPORTED','DISPROVED','FAILED','NOT_CERTIFIED'}:
                raise Conflict('tool_result_does_not_satisfy_goal')
            entry={'stepId':step['id'],'tool':step['tool'],'inputHash':call['arguments_hash'],'result':result}
            if step['tool']=='write_report_version':
                path=self.files.directory(row['owner'],'artifacts')/result['relative_path']
                entry['artifact']={'id':result['artifact_id'],'format':'md','sha256':result['sha256'],'content':path.read_text(encoding='utf-8')}
            elif step['tool'] in DOCUMENT_TOOLS:
                entry['artifact']={'id':result['artifact_id'],'format':result['extension'],'sha256':result['sha256'],'content':result['content'],
                    'sourceId':result['source']['id'],'sourceHash':result['source']['sha256'],'textTruncated':result['textTruncated'],
                    'warnings':result['warningsSummary'],'warningsTruncated':result['warningsTruncated']}
            elif result.get('artifacts'):
                entry['artifacts']=result['artifacts']
            evidence.append(entry);by_id[step['id']]=entry
        for rule in plan['acceptance']:
            entry=by_id[rule['stepId']]
            if rule['kind']=='artifact_content':
                items=([entry['artifact']] if 'artifact' in entry else entry.get('artifacts',[]))
                if not any(a.get('format')==rule['format'] and all(t in a.get('content','') for t in rule['contains']) and
                           (not rule.get('sourceId') or a.get('sourceId')==rule['sourceId'] and a.get('sourceHash')==rule.get('sourceHash')) for a in items):
                    raise Conflict('artifact_predicate_not_satisfied')
            elif entry['tool']=='write_report_version' or 'artifact' in entry or entry.get('artifacts'):
                raise Conflict('artifact_goal_requires_artifact_predicate')
        return {'goal':row['goal'],'goalRevision':job['goal_revision'],'evidenceHash':digest(evidence),'evidence':evidence}

    def evidence(self, token, value):
        fields(value,'owner taskId fence instance')
        with self.authority.request(token,value['owner'],roles=('worker',)) as (db,actor):
            row,job=self._worker(db,actor,value)
            return self._evidence(db,row,job)

    def complete(self, token, value):
        fields(value,'owner taskId fence instance answer semantic')
        if not isinstance(value['answer'],str) or len(value['answer'].encode())>12000:raise ValueError('summary_limit')
        with self.authority.request(token,value['owner'],roles=('worker',)) as (db,actor):
            row,job=self._worker(db,actor,value)
            if self.store._unsafe(db,row['id']):raise UncertainEffect('unresolved_request_is_not_goal_success')
            actual=self._evidence(db,row,job)
            semantic=value['semantic']
            if not isinstance(semantic,dict) or semantic.get('accepted') is not True or semantic.get('goalRevision')!=actual['goalRevision'] or semantic.get('evidenceHash')!=actual['evidenceHash']:
                raise Conflict('whole_goal_not_verified')
            artifacts=[]
            for entry in actual['evidence']:
                if 'artifact' in entry:
                    artifacts.append(dict({k:v for k,v in entry['artifact'].items() if k!='content'},origin='cloud'))
                artifacts.extend(dict({k:v for k,v in artifact.items() if k not in {'content','contentComplete'}},origin='device') for artifact in entry.get('artifacts',[]))
            self.store.complete(Lease(row['id'],row['owner'],row['fence']),{'answer':value['answer'],'goalRevision':actual['goalRevision'],
                'evidenceHash':actual['evidenceHash'],'artifacts':artifacts})
            return {'state':'SUCCEEDED','taskId':row['id']}

    def wait(self, token, value):
        fields(value,'owner taskId fence instance state reason')
        if value['state'] not in {'WAITING_INPUT','WAITING_DEVICE'}:raise ValueError('invalid_wait_state')
        with self.authority.request(token,value['owner'],roles=('worker',)) as (db,actor):
            row,job=self._worker(db,actor,value)
            self.store.wait(Lease(row['id'],row['owner'],row['fence']),value['state'],value['reason'],foreground_required=value['state']=='WAITING_DEVICE')
            db.execute('UPDATE net_jobs SET input_blocked=?,device_blocked=?,device_confirmation=? WHERE task_id=?',
                       (int(value['state']=='WAITING_INPUT'),int(value['state']=='WAITING_DEVICE'),int(value['state']=='WAITING_DEVICE'),row['id']))
            return {'state':value['state']}

    def control(self, token, command_id, value):
        fields(value,'owner taskId revision action payload')
        with self.authority.request(token,value['owner'],roles=('device',)) as (db,actor):
            row,job=self._owned_job(db,actor,value['owner'],value['taskId'])
            old=self.authority.command(db,actor['id'],command_id,'task.control',value)
            if old is not None:return old
            if value['revision']!=row['revision']:raise Conflict('stale_task_revision')
            action=value['action']
            if action not in ('pause','cancel','revoke'):self.authority.domain.require_active()
            if action in ('pause','cancel'):
                getattr(self.store,action)(row['owner'],row['id'])
                db.execute("UPDATE net_instances SET state='STOP_REQUESTED' WHERE task_id=? AND state='ACTIVE'",(row['id'],))
            elif action=='revoke':
                self.store.change_authorization(row['owner'],row['id'],'REVOKED')
                db.execute('UPDATE net_jobs SET credential_revoked=1 WHERE task_id=?',(row['id'],))
            elif action=='resume':
                if self.store._unsafe(db,row['id']):raise UncertainEffect('reconcile_before_resume')
                if row['authorization']!='ACTIVE' or row['cancel_requested']:raise AuthorizationRequired('authorization_or_cancellation_blocker')
                if job['input_blocked'] or job['device_blocked'] or job['device_confirmation']:
                    state='WAITING_INPUT' if job['input_blocked'] else 'WAITING_DEVICE'
                    db.execute('UPDATE tasks SET manual_pause=0,state=?,revision=revision+1 WHERE id=?',(state,row['id']))
                else:self.store.resume(row['owner'],row['id'],row['revision'])
            elif action in ('supplement','provide_input'):
                text=value['payload']
                if not isinstance(text,str) or not 1<=len(text)<=8000 or len(row['goal'])+len(text)>8000:raise ValueError('goal_limit')
                if row['state'] in {'RUNNING','VERIFYING','STOP_REQUESTED','UNCERTAIN_SIDE_EFFECT'}:raise Conflict('stop_and_reconcile_before_goal_change')
                if row['state']=='CANCELED':raise Conflict('task_canceled')
                if db.execute("SELECT 1 FROM net_instances WHERE task_id=? AND state!='STOPPED'",(row['id'],)).fetchone():
                    raise Conflict('original_scope_has_not_exited')
                retired=db.execute("UPDATE net_device_calls SET state='CANCELED' WHERE task_id=? AND state IN ('WAITING','READY')",(row['id'],)).rowcount
                if retired:
                    db.execute('UPDATE net_jobs SET device_blocked=1,device_confirmation=1 WHERE task_id=?',(row['id'],))
                    db.execute("UPDATE tasks SET device_confirmation=1,blocked_on='WAITING_DEVICE' WHERE id=?",(row['id'],))
                db.execute("UPDATE tasks SET goal=goal||char(10)||?,revision=revision+1,state=CASE WHEN state='SUCCEEDED' THEN 'FAILED_BLOCKED' ELSE state END WHERE id=?",(text,row['id']))
                db.execute('UPDATE net_jobs SET goal_revision=goal_revision+1,input_blocked=CASE WHEN ? THEN 0 ELSE input_blocked END WHERE task_id=?',(int(action=='provide_input'),row['id']))
                if action=='provide_input':
                    current=db.execute('SELECT * FROM net_jobs WHERE task_id=?',(row['id'],)).fetchone()
                    blocked='WAITING_DEVICE' if current['device_blocked'] or current['device_confirmation'] else None
                    db.execute('UPDATE tasks SET blocked_on=? WHERE id=?',(blocked,row['id']))
                current=self.store.get(row['owner'],row['id'])
                flags=db.execute('SELECT * FROM net_jobs WHERE task_id=?',(row['id'],)).fetchone()
                if not current['manual_pause'] and current['authorization']=='ACTIVE' and not current['cancel_requested'] and not any(flags[k] for k in ('input_blocked','device_blocked','device_confirmation')):
                    if current['state'] in {'WAITING_INPUT','WAITING_DEVICE','FAILED_BLOCKED','SUCCEEDED','PAUSED_BY_USER'}:
                        db.execute("UPDATE tasks SET state='QUEUED',blocked_on=NULL,priority=1,queue_order=?,revision=revision+1 WHERE id=?",(self.store._order(db),row['id']))
            elif action=='device_available':
                # Availability does not confirm foreground takeover, missing input, pause or revoke.
                db.execute('UPDATE net_jobs SET device_blocked=0 WHERE task_id=?',(row['id'],))
                if not job['device_confirmation'] and not job['input_blocked']:
                    self.store.device_available(row['owner'],row['id'])
            else:raise ValueError('unsupported_control')
            if action in ('cancel','revoke'):
                # Revoking/canceling a goal also withdraws its pending key checks.
                # Already-sent requests retain settlement and the global fence.
                for child in db.execute("""SELECT t.id FROM tasks t JOIN net_jobs j ON j.task_id=t.id
                        WHERE j.actor=? AND t.owner=? AND json_extract(j.snapshot_json,'$.validation.taskId')=?
                        AND t.state NOT IN ('SUCCEEDED','CANCELED')""",(actor['id'],row['owner'],row['id'])).fetchall():
                    self.store.cancel(row['owner'],child['id'])
                    db.execute("UPDATE net_instances SET state='STOP_REQUESTED' WHERE task_id=? AND state='ACTIVE'",(child['id'],))
            current=self.store.get(row['owner'],row['id'])
            return self.authority.acknowledge(db,actor['id'],command_id,{'taskId':row['id'],'state':current['state'],'revision':current['revision']})

    def update_hosting(self, token, command_id, value):
        fields(value,'owner taskId revision grant')
        validate_grant(value['grant'])
        with self.authority.request(token,value['owner'],roles=('device',)) as (db,actor):
            row,job=self._owned_job(db,actor,value['owner'],value['taskId'])
            old=self.authority.command(db,actor['id'],command_id,'hosting.update',value)
            if old is not None:return old
            self.authority.domain.require_active()
            if row['authorization'] not in {'ACTIVE','EXPIRED'} or row['cancel_requested'] or job['credential_revoked']:
                raise AuthorizationRequired('hosting_renewal_cannot_restore_authorization')
            if row['revision']!=value['revision'] or row['state'] in TERMINAL:
                raise Conflict('hosting_revision_changed')
            if self.store._unsafe(db,row['id']) or db.execute("SELECT 1 FROM net_instances WHERE task_id=? AND state!='STOPPED'",(row['id'],)).fetchone():
                raise UncertainEffect('stop_and_settle_before_hosting_update')
            validate_grant(value['grant'],fresh=True)
            snapshot=json.loads(job['snapshot_json']);snapshot['grant']=value['grant']
            db.execute('UPDATE net_jobs SET snapshot_json=? WHERE task_id=?',(canonical(snapshot),row['id']))
            self._invalidate_device_approval(db,row,job)
            db.execute('UPDATE tasks SET auth_epoch=auth_epoch+1,revision=revision+1 WHERE id=?',(row['id'],))
            self.store._event(db,self.store.get(row['owner'],row['id']),'hosting_duration_changed',grant=value['grant'])
            return self.authority.acknowledge(db,actor['id'],command_id,{'taskId':row['id'],'grant':value['grant']})

    @staticmethod
    def _invalidate_device_approval(db, row, job):
        pending=db.execute("""UPDATE net_device_calls SET grant_epoch=?,state='WAITING',
            device_actor=NULL,instance=NULL,fence=NULL WHERE task_id=? AND state IN ('WAITING','READY')""",
            (row['auth_epoch']+1,row['id'])).rowcount
        if pending:
            db.execute('UPDATE net_jobs SET device_blocked=1,device_confirmation=1 WHERE task_id=?',(row['id'],))
            db.execute('UPDATE tasks SET device_confirmation=1,blocked_on=? WHERE id=?',
                ('WAITING_INPUT' if job['input_blocked'] else 'WAITING_DEVICE',row['id']))

    def replace_credential(self, token, command_id, value):
        # Validation is itself a physical, journaled request; its result is installed only
        # if all original scope/epoch/locked configuration fields still match afterwards.
        fields(value,'owner taskId revision secret')
        with self.authority.request(token,value['owner'],roles=('device',)) as (db,actor):
            row,job=self._owned_job(db,actor,value['owner'],value['taskId'])
            old=self.authority.command(db,actor['id'],command_id,'credential.replace',value)
            if old is not None:return old
            self.authority.domain.require_active()
            if row['authorization'] not in {'ACTIVE','EXPIRED'} or row['revision']!=value['revision'] or job['credential_revoked'] or row['cancel_requested'] or row['state'] in TERMINAL:
                raise Conflict('only_unchanged_nonrevoked_grant_can_refresh')
            if db.execute("SELECT 1 FROM net_instances WHERE task_id=? AND state!='STOPPED'",(row['id'],)).fetchone() or self.store._unsafe(db,row['id']):
                raise UncertainEffect('stop_and_reconcile_before_credential_validation')
            config=self.vault.open(self.binding(row,job),job['config_cipher']);config['secret']=value['secret']
            validate_configuration(config,loopback_http=self.loopback_http)
            for pending in db.execute("""SELECT t.*,j.snapshot_json,j.config_cipher,j.credential_revision
                    FROM tasks t JOIN net_jobs j ON j.task_id=t.id WHERE j.actor=? AND t.owner=?
                    AND json_extract(j.snapshot_json,'$.validation.taskId')=?
                    AND t.state NOT IN ('SUCCEEDED','CANCELED')""",(actor['id'],row['owner'],row['id'])).fetchall():
                pending_config=self.vault.open(self.binding(pending,pending),pending['config_cipher'])
                target=json.loads(pending['snapshot_json'])['validation']
                same_scope=(target['grantEpoch']==row['auth_epoch']
                    and target['credentialRevision']==job['credential_revision']
                    and target.get('authorization','EXPIRED')==row['authorization'])
                unsafe=self.store._unsafe(db,pending['id'])
                live=db.execute("SELECT 1 FROM net_instances WHERE task_id=? AND state!='STOPPED'",(pending['id'],)).fetchone()
                if pending_config!=config or not same_scope:
                    if unsafe or live:raise UncertainEffect('prior_credential_validation_unsettled')
                    self.store.cancel(row['owner'],pending['id'])
                    continue
                # An explicit recovery may reuse a known physical success; it must not
                # create another paid request or infer settlement from process absence.
                known=db.execute("SELECT 1 FROM net_provider_calls WHERE task_id=? AND role='credential_validation' AND state='RETURNED' AND json_extract(result_json,'$.rejectedStatus') IS NULL",(pending['id'],)).fetchone()
                if pending['state']=='FAILED_BLOCKED' and not self.store._stop_state(pending) and not unsafe and not live and known:
                    self.store.resume(row['owner'],pending['id'],pending['revision'])
                state=self.store.get(row['owner'],pending['id'])['state']
                return self.authority.acknowledge(db,actor['id'],command_id,
                    {'taskId':row['id'],'validationTaskId':pending['id'],
                     'state':'VALIDATION_QUEUED' if state=='QUEUED' else 'VALIDATION_PENDING','validationState':state})
            # Encrypted pending validation is executed by the same supervised worker path.
            # Keep all independent blockers; prevent the original queued task racing validation.
            validation=self.store.enqueue(row['owner'],'credential:'+digest([row['id'],command_id]),'Validate the replacement credential for the original locked provider.',
                ModelLock(**json.loads(row['model_json'])),[],[],{},require_evidence=False)
            snapshot=json.loads(job['snapshot_json'])
            for key in ('documentService','nativeHistory','context'):snapshot.pop(key,None)
            snapshot.update(sourceId=validation,goal='Validate the replacement credential only.',requireEvidence=False,tools=[],sources=[],
                validation={'taskId':row['id'],'revision':row['revision'],'grantEpoch':row['auth_epoch'],'credentialRevision':job['credential_revision'],'authorization':row['authorization']})
            vrow=self.store.get(row['owner'],validation)
            cipher=self.vault.seal(self.binding(vrow,{'credential_revision':1}),config)
            db.execute("INSERT INTO net_jobs(task_id,mode,source_id,actor,snapshot_json,config_cipher) VALUES(?,'cloud',?,?,?,?)",(validation,validation,actor['id'],canonical(snapshot),cipher))
            if row['authorization']=='ACTIVE' and not self.store._stop_state(row):
                db.execute("UPDATE tasks SET state='WAITING_AUTHORIZATION',revision=revision+1 WHERE id=?",(row['id'],))
            return self.authority.acknowledge(db,actor['id'],command_id,{'taskId':row['id'],'validationTaskId':validation,'state':'VALIDATION_QUEUED'})

    def apply_validation(self, token, value):
        fields(value,'owner taskId fence instance')
        with self.authority.request(token,value['owner'],roles=('worker',)) as (db,actor):
            row,job=self._worker(db,actor,value)
            snapshot=json.loads(job['snapshot_json']);target=snapshot.get('validation')
            if not target:raise Conflict('not_a_credential_validation')
            original=self.store.get(row['owner'],target['taskId'])
            old=db.execute('SELECT * FROM net_jobs WHERE task_id=?',(original['id'],)).fetchone()
            if (original['authorization']!=target.get('authorization','EXPIRED') or original['cancel_requested'] or original['auth_epoch']!=target['grantEpoch'] or old['credential_revision']!=target['credentialRevision']
                    or old['credential_revoked'] or original['model_json']!=row['model_json']):raise Conflict('credential_scope_changed')
            known=db.execute("SELECT 1 FROM net_provider_calls WHERE task_id=? AND role='credential_validation' AND state='RETURNED' AND json_extract(result_json,'$.rejectedStatus') IS NULL",(row['id'],)).fetchone()
            if known is None:raise Conflict('physical_validation_receipt_required')
            config=self.vault.open(self.binding(row,job),job['config_cipher'])
            revision=old['credential_revision']+1
            cipher=self.vault.seal(self.binding(original,{'credential_revision':revision}),config)
            db.execute('UPDATE net_jobs SET config_cipher=?,credential_revision=? WHERE task_id=?',(cipher,revision,original['id']))
            # A changed key invalidates old foreground approval, never the device intent.
            self._invalidate_device_approval(db,original,old)
            # Existing reference implementation preserves device/input/manual pause on key refresh.
            self.store.restore_authorization(original['owner'],original['id'],original['auth_epoch'],json.loads(original['capabilities_json']),source='VERIFIED_REPLACEMENT')
            self.store.complete(Lease(row['id'],row['owner'],row['fence']),{'validation':'APPLIED','originalTaskId':original['id']})
            return {'state':'APPLIED','taskId':original['id']}

    def artifact(self, token, value):
        fields(value,'owner taskId artifactId'+(' offset' if 'offset' in value else ''))
        offset=value.get('offset',0)
        if type(offset) is not int or offset<0:raise ValueError('invalid_artifact_offset')
        with self.authority.request(token,value['owner'],roles=('device',)) as (db,actor):
            row,job=self._owned_job(db,actor,value['owner'],value['taskId'])
            for call in db.execute("SELECT * FROM calls WHERE task_id=? AND state='SUCCEEDED'",(row['id'],)):
                if call['tool'] not in DOCUMENT_TOOLS:continue
                result=json.loads(call['result_json'])
                if result['artifact_id']!=value['artifactId']:continue
                if not self.documents.verify(Lease(row['id'],row['owner'],call['fence']),call['call_id'],json.loads(call['arguments_json']),result):
                    raise Conflict('document_artifact_readback_failed')
                if offset>=result['size']:raise ValueError('artifact_offset_outside_version')
                return self.documents.artifact_bytes(row['owner'],result,offset=offset)
            for call in db.execute("SELECT * FROM calls WHERE task_id=? AND tool='write_report_version' AND state='SUCCEEDED'",(row['id'],)):
                result=json.loads(call['result_json']);args=json.loads(call['arguments_json'])
                if result['artifact_id']!=value['artifactId']:continue
                if not self.files.verify_report(Lease(row['id'],row['owner'],call['fence']),call['call_id'],args,result):raise Conflict('artifact_readback_failed')
                data=(self.files.directory(row['owner'],'artifacts')/result['relative_path']).read_bytes()
                if offset:raise ValueError('text_artifact_has_no_more_chunks')
                return {'artifactId':result['artifact_id'],'sha256':result['sha256'],'extension':'md','bytes':base64.b64encode(data).decode(),'parentId':result['parent_artifact_id']}
            raise KeyError('artifact_not_owned')

    def device_pending(self, token, value):
        fields(value,'owner taskId')
        with self.authority.request(token,value['owner'],roles=('device',)) as (db,actor):
            row,job=self._owned_job(db,actor,value['owner'],value['taskId'])
            pending=db.execute("SELECT * FROM net_device_calls WHERE task_id=? AND state IN ('WAITING','READY','IN_FLIGHT','UNKNOWN') ORDER BY rowid LIMIT 1",(row['id'],)).fetchone()
            return {'request':None if pending is None else {'callId':pending['id'],'tool':pending['tool'],'arguments':json.loads(pending['input_json']),
                'inputHash':pending['input_hash'],'grantEpoch':pending['grant_epoch'],'state':pending['state'],'taskRevision':row['revision']}}

    def _signature(self, actor, message, signature):
        serialization.load_pem_public_key(actor['public_key'].encode()).verify(base64.b64decode(signature,validate=True),canonical(message).encode(),padding.PKCS1v15(),hashes.SHA256())

    def device_confirm(self, token, command_id, value):
        fields(value,'owner taskId callId revision inputHash grantEpoch foregroundNonce signature')
        with self.authority.request(token,value['owner'],roles=('device',)) as (db,actor):
            row,job=self._owned_job(db,actor,value['owner'],value['taskId'])
            old=self.authority.command(db,actor['id'],command_id,'device.confirm',value)
            if old is not None:return old
            self._signature(actor,{k:v for k,v in value.items() if k!='signature'},value['signature'])
            pending=db.execute('SELECT * FROM net_device_calls WHERE task_id=? AND id=?',(row['id'],value['callId'])).fetchone()
            if job['planned_revision']!=job['goal_revision']:raise Conflict('old_device_input_requires_fresh_plan')
            if pending is None or pending['state']!='WAITING' or pending['input_hash']!=value['inputHash'] or pending['grant_epoch']!=value['grantEpoch'] or row['auth_epoch']!=value['grantEpoch'] or row['revision']!=value['revision']:
                raise Conflict('device_confirmation_changed')
            if row['manual_pause'] or row['cancel_requested'] or row['authorization']!='ACTIVE' or job['input_blocked'] or self.store._unsafe(db,row['id']):
                raise Conflict('independent_device_blocker')
            if db.execute("SELECT 1 FROM net_instances WHERE task_id=? AND state!='STOPPED'",(row['id'],)).fetchone():raise Conflict('old_executor_not_stopped')
            identifier(value['foregroundNonce'])
            db.execute("UPDATE net_device_calls SET state='READY',device_actor=? WHERE task_id=? AND id=?",(actor['id'],row['id'],pending['id']))
            db.execute('UPDATE net_jobs SET device_blocked=0,device_confirmation=0 WHERE task_id=?',(row['id'],))
            db.execute("UPDATE tasks SET device_confirmation=0,blocked_on=NULL,state='QUEUED',priority=1,queue_order=?,revision=revision+1 WHERE id=?",(self.store._order(db),row['id']))
            return self.authority.acknowledge(db,actor['id'],command_id,{'state':'QUEUED','callId':pending['id']})

    def device_claim(self, token, value):
        fields(value,'owner taskId callId instance')
        with self.authority.request(token,value['owner'],roles=('device',)) as (db,actor):
            row,job=self._owned_job(db,actor,value['owner'],value['taskId'])
            pending=db.execute('SELECT * FROM net_device_calls WHERE task_id=? AND id=?',(row['id'],value['callId'])).fetchone()
            if pending is None or pending['device_actor']!=actor['id'] or pending['state'] not in {'READY','IN_FLIGHT'}:
                raise Conflict('device_action_not_ready')
            lease=self.authority.claim(token,{k:value[k] for k in ('owner','taskId','instance')},mode='device')
            if lease['state']!='RUNNING':return lease
            if pending['state']=='IN_FLIGHT':
                # Repeated ACK is not new permission to perform the native action.
                return dict(lease,dispatch=False)
            permit=self.store.prepare_call(Lease(row['id'],row['owner'],lease['fence']),pending['id'],pending['tool'],json.loads(pending['input_json']),
                'device.'+pending['tool'],'EXTERNAL',ModelLock(**json.loads(row['model_json'])))
            self.store.dispatch(permit)
            db.execute("UPDATE net_device_calls SET state='IN_FLIGHT',instance=?,fence=? WHERE task_id=? AND id=?",(value['instance'],lease['fence'],row['id'],pending['id']))
            return dict(lease,dispatch=True,tool=pending['tool'],arguments=json.loads(pending['input_json']),inputHash=pending['input_hash'],grantEpoch=pending['grant_epoch'])

    def device_finish(self, token, value):
        fields(value,'owner taskId callId instance fence result signature')
        with self.authority.request(token,value['owner'],roles=('device',),settlement=True) as (db,actor):
            row,job=self._owned_job(db,actor,value['owner'],value['taskId'])
            self._signature(actor,{k:v for k,v in value.items() if k!='signature'},value['signature'])
            pending=db.execute('SELECT * FROM net_device_calls WHERE task_id=? AND id=?',(row['id'],value['callId'])).fetchone()
            if pending is None or pending['device_actor']!=actor['id'] or pending['instance']!=value['instance'] or pending['fence']!=value['fence']:
                raise Conflict('native_receipt_not_bound')
            result=value['result'];fields(result,'tool inputHash nativeTaskId nativeFence output artifacts verifier')
            if result['tool']!=pending['tool'] or result['inputHash']!=pending['input_hash'] or result['nativeTaskId']!=job['source_id']:
                raise Conflict('native_receipt_intent_changed')
            expected='ORIGINAL_NATIVE_VERIFIER' if pending['tool'] not in {'capture_device_screen','device_screen_action'} else 'FOREGROUND_DEVICE_READBACK'
            if result['verifier']!=expected:raise ValueError('unregistered_native_verifier')
            if pending['state']=='SUCCEEDED':
                if pending['result_hash']!=digest(result):raise Conflict('native_receipt_changed')
                return {'recorded':True}
            self.authority._bound(db,actor,row['owner'],row['id'],value['fence'],value['instance'],dispatch=False)
            if pending['state']!='IN_FLIGHT':raise UncertainEffect('device_result_not_pending')
            # The signed adapter proof attests original native independent readback, not model text.
            permit=Permit(Lease(row['id'],row['owner'],value['fence']),pending['id'],pending['grant_epoch'],pending['input_hash'])
            self.store.commit_result(permit,result,{'verified':True,'method':expected,'result_sha256':digest(result)})
            db.execute("UPDATE net_device_calls SET state='SUCCEEDED',result_json=?,result_hash=? WHERE task_id=? AND id=?",(canonical(result),digest(result),row['id'],pending['id']))
            db.execute("UPDATE net_instances SET state='STOPPED',stop_hash=? WHERE task_id=? AND fence=?",(digest(value),row['id'],value['fence']))
            if not self.store._unsafe(db,row['id']):
                current=self.store.get(row['owner'],row['id'])
                state=self.store._stop_state(current) or 'QUEUED'
                db.execute('UPDATE tasks SET state=?,priority=1,queue_order=?,revision=revision+1 WHERE id=?',(state,self.store._order(db),row['id']))
                TaskStore._release(db,row['id'])
            return {'recorded':True}

    def device_recover(self, token, value):
        """Settlement metadata, not permission to replay an action or disclose new content."""
        fields(value,'owner taskId callId instance')
        with self.authority.request(token,value['owner'],roles=('device',),settlement=True) as (db,actor):
            row,job=self._owned_job(db,actor,value['owner'],value['taskId'])
            pending=db.execute('SELECT * FROM net_device_calls WHERE task_id=? AND id=?',(row['id'],value['callId'])).fetchone()
            if pending is None or pending['device_actor']!=actor['id'] or pending['instance']!=value['instance']:
                raise Conflict('device_settlement_not_bound')
            return {'fence':pending['fence'],'inputHash':pending['input_hash'],'state':pending['state']}

    def device_abort(self, token, value):
        fields(value,'owner taskId callId instance fence proof signature')
        with self.authority.request(token,value['owner'],roles=('device',),settlement=True) as (db,actor):
            row,job=self._owned_job(db,actor,value['owner'],value['taskId'])
            self._signature(actor,{k:v for k,v in value.items() if k!='signature'},value['signature'])
            proof=value['proof'];fields(proof,'sourceId sourceFence state journalHash nativeCallId callState')
            if (proof['sourceId']!=job['source_id'] or type(proof['sourceFence']) is not int or proof['sourceFence']<0
                    or proof['state'] not in {'QUEUED','INTERRUPTED','WAITING_INPUT','WAITING_DEVICE','WAITING_AUTHORIZATION','PAUSED_BY_USER','FAILED_BLOCKED','COMPLETED_UNVERIFIED','CANCELED','SUCCEEDED'}
                    or not isinstance(proof['journalHash'],str) or not re.fullmatch('[0-9a-f]{64}',proof['journalHash'])
                    or proof['nativeCallId']!='cloud_'+raw_sha(canonical(row['id'])+canonical(value['callId']))[:40]
                    or proof['callState']!='NOT_DISPATCHED'):
                raise UncertainEffect('native_dispatch_not_proven_absent')
            binding=db.execute('SELECT * FROM net_instances WHERE task_id=? AND fence=?',(row['id'],value['fence'])).fetchone()
            if binding is None or binding['actor']!=actor['id'] or binding['instance']!=value['instance']:
                raise Conflict('native_stop_not_bound')
            if binding['state']=='STOPPED':
                if binding['stop_hash']!=digest(value):raise Conflict('conflicting_device_stop_receipt')
                return {'recorded':True}
            pending=db.execute('SELECT * FROM net_device_calls WHERE task_id=? AND id=?',(row['id'],value['callId'])).fetchone()
            self.authority._bound(db,actor,row['owner'],row['id'],value['fence'],value['instance'],dispatch=False)
            if pending is None or pending['state']!='IN_FLIGHT' or pending['instance']!=value['instance'] or pending['fence']!=value['fence']:
                raise Conflict('native_dispatch_not_pending')
            db.execute("UPDATE calls SET state='FAILED_SAFE',reason='NATIVE_NOT_DISPATCHED',updated=? WHERE task_id=? AND call_id=? AND state='IN_FLIGHT'",(time.time(),row['id'],pending['id']))
            db.execute("UPDATE net_instances SET state='STOPPED',stop_hash=? WHERE task_id=? AND fence=?",(digest(value),row['id'],value['fence']))
            db.execute("UPDATE net_device_calls SET state='WAITING',device_actor=NULL,instance=NULL,fence=NULL,grant_epoch=? WHERE task_id=? AND id=?",(row['auth_epoch'],row['id'],pending['id']))
            # Keep the original row and signed stop witness. Only a fresh foreground
            # grant/new fence may prepare this proven-not-dispatched logical call again.
            self.store._event(db,row,'NATIVE_NOT_DISPATCHED',call_id=pending['id'],proof=value)
            db.execute('UPDATE net_jobs SET device_blocked=1,device_confirmation=1 WHERE task_id=?',(row['id'],))
            current=self.store.get(row['owner'],row['id'])
            state=self.store._stop_state(current) or 'WAITING_DEVICE'
            db.execute('UPDATE tasks SET state=?,blocked_on=?,device_confirmation=1,revision=revision+1 WHERE id=?',(state,'WAITING_DEVICE',row['id']))
            if not self.store._unsafe(db,row['id']):TaskStore._release(db,row['id'])
            return {'recorded':True}

    def confirm_replan(self, token, command_id, value):
        fields(value,'owner taskId revision grantEpoch goalHash foregroundNonce signature')
        with self.authority.request(token,value['owner'],roles=('device',)) as (db,actor):
            row,job=self._owned_job(db,actor,value['owner'],value['taskId'])
            old=self.authority.command(db,actor['id'],command_id,'device.confirm_replan',value)
            if old is not None:return old
            self._signature(actor,{k:v for k,v in value.items() if k!='signature'},value['signature'])
            if row['revision']!=value['revision'] or row['auth_epoch']!=value['grantEpoch'] or raw_sha(row['goal'])!=value['goalHash']:
                raise Conflict('replan_confirmation_changed')
            identifier(value['foregroundNonce'])
            if row['manual_pause'] or row['cancel_requested'] or row['authorization']!='ACTIVE' or job['input_blocked']:
                raise Conflict('independent_replan_blocker')
            if job['planned_revision']==job['goal_revision'] or not job['device_confirmation']:
                raise Conflict('no_changed_goal_confirmation')
            if self.store._unsafe(db,row['id']) or db.execute("SELECT 1 FROM net_instances WHERE task_id=? AND state!='STOPPED'",(row['id'],)).fetchone():
                raise UncertainEffect('old_scope_not_quiescent')
            db.execute('UPDATE net_jobs SET device_blocked=0,device_confirmation=0 WHERE task_id=?',(row['id'],))
            db.execute("UPDATE tasks SET blocked_on=NULL,device_confirmation=0,state='QUEUED',priority=1,queue_order=?,revision=revision+1 WHERE id=?",(self.store._order(db),row['id']))
            return self.authority.acknowledge(db,actor['id'],command_id,{'state':'QUEUED','taskId':row['id']})
