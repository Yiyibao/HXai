"""One physical HTTP attempt using the accepted provider/model/reasoning snapshot.

No routing, proxy inheritance, redirect following, retry or credential validation
outside the journaled call. HTTPS is required except explicitly enabled loopback QA.
"""
from __future__ import annotations
import json
import ssl
import urllib.error
import urllib.request
from urllib.parse import urlsplit
from .store import canonical


class RejectedProvider(Exception):
    def __init__(self, status):
        self.status=status
        super().__init__('provider_rejected')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_configuration(value, *, loopback_http=False):
    if not isinstance(value,dict) or set(value)!={'protocol','endpoint','wireModel','baseBody','headers','secret'}:
        raise ValueError('invalid_provider_configuration')
    if value['protocol'] not in {'openai_chat','openai_responses','claude','google'}:
        raise ValueError('unsupported_provider_protocol')
    parts=urlsplit(value['endpoint'])
    if (parts.scheme!='https' and not (loopback_http and parts.scheme=='http' and parts.hostname in {'127.0.0.1','::1','localhost'})) or not parts.hostname or parts.username or parts.password or parts.fragment or parts.query:
        raise ValueError('provider_endpoint_requires_explicit_https_origin')
    if not isinstance(value['wireModel'],str) or not 1<=len(value['wireModel'])<=200:
        raise ValueError('invalid_wire_model')
    if not isinstance(value['secret'],str) or not 1<=len(value['secret'])<=65536 or '\r' in value['secret'] or '\n' in value['secret']:
        raise ValueError('invalid_provider_secret')
    if not isinstance(value['baseBody'],dict) or set(value['baseBody']) & {'model','messages','input','contents','tools','stream','system'}:
        raise ValueError('provider_body_cannot_replace_execution_fields')
    if not isinstance(value['headers'],dict) or len(value['headers'])>30:
        raise ValueError('invalid_provider_headers')
    for k,v in value['headers'].items():
        if not isinstance(k,str) or not isinstance(v,str) or any(c in k+v for c in '\r\n') or k.lower() in {'host','content-length','authorization','x-api-key','x-goog-api-key','connection','transfer-encoding'}:
            raise ValueError('provider_header_override_denied')
    if len(canonical(value).encode())>100000:
        raise ValueError('provider_configuration_too_large')
    return value


def encode_request(configuration, messages, tools):
    protocol=configuration['protocol'];body=dict(configuration['baseBody']);model=configuration['wireModel']
    if protocol=='openai_chat':
        body.update(model=model,messages=messages,stream=False)
        if tools: body['tools']=[{'type':'function','function':t} for t in tools]
    elif protocol=='openai_responses':
        converted=[]
        for message in messages:
            if message['role']=='tool':
                converted.append({'type':'function_call_output','call_id':message['tool_call_id'],'output':message['content']})
            elif message.get('tool_calls'):
                if message.get('content'): converted.append({'role':'assistant','content':message['content']})
                converted.extend({'type':'function_call','call_id':t['id'],'name':t['function']['name'],'arguments':t['function']['arguments']} for t in message['tool_calls'])
            else: converted.append({'role':message['role'],'content':message['content']})
        body.update(model=model,input=converted,stream=False)
        if tools: body['tools']=[{'type':'function',**t} for t in tools]
    elif protocol=='claude':
        converted=[];system=[]
        for message in messages:
            if message['role']=='system': system.append(message['content']);continue
            if message['role']=='tool':
                converted.append({'role':'user','content':[{'type':'tool_result','tool_use_id':message['tool_call_id'],'content':message['content']}]})
            else:
                content=[]
                if message.get('content'): content.append({'type':'text','text':message['content']})
                content.extend({'type':'tool_use','id':t['id'],'name':t['function']['name'],'input':json.loads(t['function']['arguments'])} for t in message.get('tool_calls',[]))
                converted.append({'role':message['role'],'content':content})
        body.update(model=model,messages=converted,system='\n\n'.join(system),stream=False)
        body.setdefault('max_tokens',4096)
        if tools: body['tools']=[{'name':t['name'],'description':t['description'],'input_schema':t['parameters']} for t in tools]
    else:
        converted=[];system=[]
        for message in messages:
            if message['role']=='system': system.append({'text':message['content']});continue
            if message['role']=='tool':
                converted.append({'role':'user','parts':[{'functionResponse':{'name':message['name'],'response':{'result':message['content']}}}]})
            else:
                parts=[]
                if message.get('content'): parts.append({'text':message['content']})
                parts.extend({'functionCall':{'name':t['function']['name'],'args':json.loads(t['function']['arguments'])}} for t in message.get('tool_calls',[]))
                converted.append({'role':'model' if message['role']=='assistant' else 'user','parts':parts})
        body.update(contents=converted)
        if system:body['systemInstruction']={'parts':system}
        if tools:body['tools']=[{'functionDeclarations':tools}]
    return body


def request(configuration, body, request_id, *, loopback_http=False):
    validate_configuration(configuration,loopback_http=loopback_http)
    protocol=configuration['protocol'];endpoint=configuration['endpoint']
    headers={'Content-Type':'application/json','Accept':'application/json','Idempotency-Key':request_id,**configuration['headers']}
    if protocol=='claude':headers.update({'x-api-key':configuration['secret'],'anthropic-version':'2023-06-01'})
    elif protocol=='google':headers['x-goog-api-key']=configuration['secret']
    else:headers['Authorization']='Bearer '+configuration['secret']
    if protocol!='google' and body.get('model')!=configuration['wireModel']:
        raise ValueError('physical_model_lock_mismatch')
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    req=urllib.request.Request(endpoint,data=canonical(body).encode(),headers=headers,method='POST')
    try:
        with opener.open(req,timeout=90) as response:
            raw=response.read(1048577)
            if len(raw)>1048576:raise ValueError('provider_response_limit')
            value=json.loads(raw)
    except urllib.error.HTTPError as error:
        # Do not consume or print arbitrary error bodies containing private context.
        raise RejectedProvider(error.code) from None
    reply=decode_response(protocol,value)
    report=value.get('usage') or value.get('usageMetadata') or {}
    total=report.get('total_tokens') if protocol in ('openai_chat','openai_responses') else report.get('totalTokenCount') if protocol=='google' else None
    reply['totalTokens']=total if type(total) is int and 0<=total<=2**31-1 else None
    reply['costUsd']=None;reply['costSource']=None
    if protocol=='openai_chat' and endpoint=='https://openrouter.ai/api/v1/chat/completions':
        from decimal import Decimal, InvalidOperation
        candidate=report.get('cost')
        try:
            amount=Decimal(str(candidate))
            if amount.is_finite() and amount>=0 and len(str(candidate))<=64:
                reply['costUsd']=str(candidate);reply['costSource']=endpoint
        except (InvalidOperation,ValueError):pass
    return reply


def decode_response(protocol, value):
    text='';calls=[];usage=None
    if protocol=='openai_chat':
        message=value['choices'][0]['message'];text=message.get('content') or '';calls=message.get('tool_calls') or []
        report=value.get('usage')
        if report:usage={'input':report.get('prompt_tokens'),'output':report.get('completion_tokens'),'cached':(report.get('prompt_tokens_details') or {}).get('cached_tokens')}
    elif protocol=='openai_responses':
        for item in value.get('output',[]):
            if item.get('type')=='message':text+=''.join(p.get('text','') for p in item.get('content',[]) if p.get('type')=='output_text')
            if item.get('type')=='function_call':calls.append({'id':item['call_id'],'type':'function','function':{'name':item['name'],'arguments':item['arguments']}})
        report=value.get('usage')
        if report:usage={'input':report.get('input_tokens'),'output':report.get('output_tokens'),'cached':(report.get('input_tokens_details') or {}).get('cached_tokens')}
    elif protocol=='claude':
        for item in value['content']:
            if item.get('type')=='text':text+=item['text']
            if item.get('type')=='tool_use':calls.append({'id':item['id'],'type':'function','function':{'name':item['name'],'arguments':canonical(item['input'])}})
        report=value.get('usage')
        if report:usage={'input':report.get('input_tokens'),'output':report.get('output_tokens'),'cached':report.get('cache_read_input_tokens')}
    else:
        for i,item in enumerate(value['candidates'][0]['content']['parts']):
            if 'text' in item and not item.get('thought'):text+=item['text']
            if 'functionCall' in item:calls.append({'id':'google_'+str(i),'type':'function','function':{'name':item['functionCall']['name'],'arguments':canonical(item['functionCall'].get('args',{}))}})
        report=value.get('usageMetadata')
        if report:usage={'input':report.get('promptTokenCount'),'output':report.get('candidatesTokenCount'),'cached':report.get('cachedContentTokenCount')}
    if not isinstance(text,str) or not isinstance(calls,list) or len(calls)>32:
        raise ValueError('invalid_provider_response')
    for call in calls:
        if not isinstance(call.get('id'),str) or not isinstance(call.get('function'),dict):raise ValueError('invalid_tool_call')
        if not isinstance(call['function'].get('name'),str) or not isinstance(call['function'].get('arguments'),str):raise ValueError('invalid_tool_call')
    if len({c['id'] for c in calls})!=len(calls):raise ValueError('duplicate_tool_call')
    if usage is not None:
        for v in usage.values():
            if v is not None and (type(v) is not int or not 0<=v<=2**63-1):raise ValueError('invalid_provider_usage')
        if not any(v is not None for v in usage.values()):usage=None
    return {'text':text,'toolCalls':calls,'usage':usage}
