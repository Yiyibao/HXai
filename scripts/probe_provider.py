"""S0 desktop probe for an explicitly selected OpenAI-compatible Chat protocol.

No API calls without invocation and environment credentials. Synthetic input only.
Does not establish Android UI, cancellation, vision, or extraction correctness.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


class ProbeError(ValueError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ProbeError('redirect_rejected')


def post(url, key, body):
    request = urllib.request.Request(url, data=json.dumps(body).encode(), headers={
        'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key}, method='POST')
    return urllib.request.build_opener(NoRedirect).open(request, timeout=45)


def complete(url, key, body):
    with post(url, key, body) as response:
        raw = response.read(2_000_001)
    if len(raw) > 2_000_000:
        raise ProbeError('response_too_large')
    data = json.loads(raw)
    choice = data['choices'][0]
    if choice.get('finish_reason') not in ('stop', 'tool_calls'):
        raise ProbeError('completion_not_finished')
    return choice['message']


def text_probe(url, key, model):
    message = complete(url, key, {'model': model, 'messages': [
        {'role': 'user', 'content': '这是合成连接测试。请只回复 MATH_S0_OK。'}], 'stream': False})
    if 'MATH_S0_OK' not in (message.get('content') or ''):
        raise ProbeError('text_marker_missing')
    return {'status': 'PASS', 'text_marker_received': True}


def stream_probe(url, key, model):
    body = {'model': model, 'messages': [{'role': 'user', 'content':
        '这是合成流式测试。请用中文分十步推导等差数列前 n 项求和公式，每步简述理由。'}], 'stream': True}
    chunks, chars, done, finished, received = 0, 0, False, False, 0
    with post(url, key, body) as response:
        if 'text/event-stream' not in response.headers.get('Content-Type', ''):
            raise ProbeError('not_event_stream')
        for raw in response:
            received += len(raw)
            if received > 2_000_000:
                raise ProbeError('stream_too_large')
            line = raw.decode('utf-8').strip()
            if not line.startswith('data:'):
                continue
            value = line[5:].strip()
            if value == '[DONE]':
                done = True
                break
            data = json.loads(value)
            for choice in data.get('choices', []):
                text = choice.get('delta', {}).get('content') or ''
                if not isinstance(text, str):
                    raise ProbeError('unsupported_content_shape')
                if text:
                    chunks += 1
                    chars += len(text)
                if choice.get('finish_reason') == 'stop':
                    finished = True
    if not done or not finished or chunks < 2 or chars < 20:
        raise ProbeError('stream_not_complete_or_incremental')
    return {'status': 'PASS', 'content_chunks': chunks, 'characters': chars,
            'done_received': done, 'finish_received': finished}


def tool_probe(url, key, model):
    messages = [{'role': 'user', 'content': '必须调用 s0_add 计算 17+25，收到工具结果后回复 S0_TOOL_OK:42。'}]
    tool = {'type': 'function', 'function': {'name': 's0_add', 'description': 'Add two fixed test integers.',
        'parameters': {'type': 'object', 'properties': {'a': {'type': 'integer'}, 'b': {'type': 'integer'}},
                       'required': ['a', 'b'], 'additionalProperties': False}}}
    first = complete(url, key, {'model': model, 'messages': messages, 'tools': [tool],
        'tool_choice': {'type': 'function', 'function': {'name': 's0_add'}}, 'stream': False})
    calls = first.get('tool_calls', [])
    if len(calls) != 1:
        raise ProbeError('tool_call_missing')
    call = calls[0]
    if call.get('type') != 'function' or not isinstance(call.get('id'), str) or not call['id'] or call['function']['name'] != 's0_add':
        raise ProbeError('invalid_tool')
    args = json.loads(call['function']['arguments'])
    if args != {'a': 17, 'b': 25} or any(type(args[k]) is not int for k in ('a', 'b')):
        raise ProbeError('invalid_tool_arguments')
    result = args['a'] + args['b']
    messages += [first, {'role': 'tool', 'tool_call_id': call['id'], 'content': json.dumps({'sum': result})}]
    last = complete(url, key, {'model': model, 'messages': messages, 'stream': False})
    if 'S0_TOOL_OK:42' not in (last.get('content') or ''):
        raise ProbeError('tool_followup_missing')
    return {'status': 'PASS', 'tool_calls': 1, 'validated_result': result, 'followup_received': True}


def run_probe(fn, url, key, model):
    try:
        return fn(url, key, model)
    except urllib.error.HTTPError as error:
        return {'status': 'FAIL', 'reason': 'http_error', 'http_status': error.code}
    except ProbeError as error:
        return {'status': 'FAIL', 'reason': str(error)}
    except (TimeoutError, urllib.error.URLError):
        return {'status': 'FAIL', 'reason': 'network_or_timeout'}
    except (ValueError, KeyError, TypeError, IndexError):
        return {'status': 'FAIL', 'reason': 'protocol_shape_error'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', required=True, choices=['openai-chat'])
    args = parser.parse_args()
    base, model, key = (os.environ.get(k, '').strip() for k in ('MATHSTUDY_S0_BASE_URL', 'MATHSTUDY_S0_MODEL', 'MATHSTUDY_S0_API_KEY'))
    parsed = urllib.parse.urlsplit(base)
    if not base or not model or not key:
        parser.error('Set MATHSTUDY_S0_BASE_URL, MATHSTUDY_S0_MODEL, MATHSTUDY_S0_API_KEY locally; never put a key in the command.')
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        parser.error('Use an HTTPS API base URL without credentials, query or fragment.')
    url = base.rstrip('/') + '/chat/completions'
    results = {'checked_at': datetime.now(timezone.utc).isoformat(), 'protocol': args.protocol,
        'model': model, 'environment': 'DESKTOP_REAL_PROVIDER', 'text': run_probe(text_probe, url, key, model),
        'stream': run_probe(stream_probe, url, key, model), 'tools': run_probe(tool_probe, url, key, model),
        'android_ui_cancel_error': 'NOT_RUN', 'vision': 'NOT_RUN', 'structured_extraction': 'NOT_RUN'}
    target = ROOT / 'qa/s0/private/provider.json'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(results, ensure_ascii=False, indent=2))
    raise SystemExit(0 if all(results[k]['status'] == 'PASS' for k in ('text', 'stream', 'tools')) else 1)
