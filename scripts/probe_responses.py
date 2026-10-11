"""Bounded S0 Responses probe. Secrets and model output are never saved.

Uses environment credentials or an explicitly selected local TOML provider.
Only synthetic input and the fixed s0_add tool are sent. No protocol fallback.
"""
import argparse
import getpass
from datetime import datetime, timezone
import json
import os
import sys
from pathlib import Path
import time
import tomllib
import urllib.error
import urllib.parse
import urllib.request

from probe_provider import NoRedirect, ProbeError
from validate_candidates import validate, CandidateError

ROOT = Path(__file__).resolve().parents[1]
LIMIT = 2_000_000


def require_base(base):
    parsed = urllib.parse.urlsplit(base)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username or
            parsed.password or parsed.query or parsed.fragment):
        raise ProbeError('invalid_base_url')
    return base.rstrip('/')


class ResponsesProbe:
    def __init__(self, base, key, model, headers=None, effort='medium'):
        self.base = require_base(base)
        self.model, self.effort = model, effort
        self.headers = {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key,
                        'User-Agent': 'MathStudyAgent-S0/0.1', 'Accept': 'application/json'}
        for name, value in (headers or {}).items():
            if name.lower() in ('host', 'authorization', 'content-type', 'content-length'):
                raise ProbeError('reserved_custom_header')
            if '\r' in name + value or '\n' in name + value:
                raise ProbeError('invalid_custom_header')
            self.headers[name] = value
        self.opener = urllib.request.build_opener(NoRedirect)

    def request(self, input_items, **options):
        body = {'model': self.model, 'input': input_items, 'store': False,
                'reasoning': {'effort': self.effort}, 'max_output_tokens': 4096,
                'include': ['reasoning.encrypted_content'], **options}
        request = urllib.request.Request(self.base + '/responses',
            data=json.dumps(body).encode(), headers=self.headers, method='POST')
        return self.opener.open(request, timeout=60)

    def complete(self, items, **options):
        with self.request(items, stream=False, **options) as response:
            raw = response.read(LIMIT + 1)
        if len(raw) > LIMIT:
            raise ProbeError('response_too_large')
        result = json.loads(raw)
        if result.get('status') != 'completed' or not isinstance(result.get('output'), list):
            raise ProbeError('response_not_completed')
        return result

    @staticmethod
    def text(result):
        return ''.join(part.get('text', '') for item in result['output']
            if item.get('type') == 'message' for part in item.get('content', [])
            if part.get('type') == 'output_text')

    def text_probe(self):
        result = self.complete([{'role': 'user', 'content': '合成连接测试：只回复 MATH_S0_OK。'}])
        if 'MATH_S0_OK' not in self.text(result):
            raise ProbeError('text_marker_missing')
        return {'status': 'PASS', 'text_marker_received': True}

    def stream_probe(self):
        chunks, characters, received = 0, 0, 0
        completed, accumulated, response_id = False, '', None
        started = time.monotonic()
        with self.request([{'role': 'user', 'content':
                '合成流式测试：用中文分十步推导等差数列前 n 项求和公式，每步简述理由。'}], stream=True) as response:
            if 'text/event-stream' not in response.headers.get('Content-Type', ''):
                raise ProbeError('not_event_stream')
            pending = []
            def consume(lines):
                nonlocal chunks, characters, completed, accumulated, response_id
                if not lines:
                    return
                value = '\n'.join(lines)
                if value == '[DONE]':
                    return
                event = json.loads(value)
                kind = event.get('type')
                if kind == 'response.created':
                    response_id = event['response']['id']
                elif kind == 'response.output_text.delta':
                    delta = event['delta']
                    if not isinstance(delta, str) or completed:
                        raise ProbeError('invalid_text_delta')
                    if delta:
                        chunks += 1
                        characters += len(delta)
                        accumulated += delta
                elif kind == 'response.completed':
                    result = event['response']
                    if (completed or result.get('status') != 'completed' or
                            response_id is None or result.get('id') != response_id or
                            self.text(result) != accumulated):
                        raise ProbeError('stream_completion_mismatch')
                    completed = True
                elif kind in ('response.failed', 'response.incomplete', 'error'):
                    raise ProbeError('stream_failed_or_incomplete')
            for raw in response:
                received += len(raw)
                if received > LIMIT or time.monotonic() - started > 120:
                    raise ProbeError('stream_limit_exceeded')
                line = raw.decode('utf-8').rstrip('\r\n')
                if not line:
                    consume(pending)
                    pending = []
                    if completed:
                        break
                elif line.startswith('data:'):
                    pending.append(line[5:].lstrip(' '))
            consume(pending)
        if not completed or chunks < 2 or characters < 20:
            raise ProbeError('stream_not_complete_or_incremental')
        return {'status': 'PASS', 'content_chunks': chunks, 'characters': characters,
                'completion_matches_deltas': True}

    def tool_probe(self):
        items = [{'role': 'user', 'content':
                  '必须调用 s0_add 计算 17+25，收到工具结果后回复 S0_TOOL_OK:42。'}]
        tool = {'type': 'function', 'name': 's0_add', 'description': 'Add two test integers.',
                'strict': True, 'parameters': {'type': 'object', 'properties': {
                    'a': {'type': 'integer'}, 'b': {'type': 'integer'}},
                    'required': ['a', 'b'], 'additionalProperties': False}}
        first = self.complete(items, tools=[tool],
            tool_choice={'type': 'function', 'name': 's0_add'}, parallel_tool_calls=False)
        calls = [item for item in first['output'] if item.get('type') == 'function_call']
        if len(calls) != 1:
            raise ProbeError('tool_call_missing_or_multiple')
        call = calls[0]
        if call.get('name') != 's0_add' or not isinstance(call.get('call_id'), str) or not call['call_id']:
            raise ProbeError('invalid_tool')
        args = json.loads(call['arguments'])
        if args != {'a': 17, 'b': 25} or any(type(args[k]) is not int for k in ('a', 'b')):
            raise ProbeError('invalid_tool_arguments')
        result = args['a'] + args['b']
        # Replay output (including encrypted reasoning) without server storage.
        items += first['output'] + [{'type': 'function_call_output',
            'call_id': call['call_id'], 'output': json.dumps({'sum': result})}]
        last = self.complete(items, tools=[tool], tool_choice='none')
        if 'S0_TOOL_OK:42' not in self.text(last):
            raise ProbeError('tool_followup_missing')
        return {'status': 'PASS', 'validated_result': result, 'tool_calls': 1,
                'followup_received': True, 'stateless_replay': True}

    def extraction_probe(self):
        prompt = ('合成记忆抽取测试。唯一允许来源 m1，原文：我喜欢先看反例。'
                  '只输出 JSON，不要 Markdown；schema_version=1，candidates 数组必须恰好一条。'
                  '字段只有 type,topic_key,fact_key,content,evidence_type,source_ids。'
                  'type=PREFERENCE，topic_key=teaching，fact_key=counterexample_first，'
                  'content 保留原文偏好，evidence_type=SELF_REPORTED，source_ids=["m1"]。'
                  '不要添加身份、掌握度、状态或修订字段。')
        response = self.complete([{'role': 'user', 'content': prompt}])
        candidates = validate(self.text(response), {'m1'})
        if (len(candidates) != 1 or candidates[0]['type'] != 'PREFERENCE' or
                candidates[0]['evidence_type'] != 'SELF_REPORTED' or
                '反例' not in candidates[0]['content']):
            raise ProbeError('synthetic_preference_missing')
        # Same real output must fail when its source does not belong to this job.
        try:
            validate(self.text(response), {'other_profile_source'})
        except CandidateError:
            rejected = True
        else:
            raise ProbeError('cross_profile_source_not_rejected')
        return {'status': 'PASS', 'candidate_count': 1, 'source_allowlist_validated': True,
                'cross_profile_source_rejected': rejected, 'android_transaction': 'NOT_RUN'}


def safe_run(fn):
    try:
        return fn()
    except urllib.error.HTTPError as error:
        raw = error.read(8192).lower()
        edge = b'cloudflare' in raw or b'just a moment' in raw or b'cf-chl-' in raw
        return {'status': 'FAIL', 'reason': 'http_error', 'http_status': error.code,
                'html_response': 'text/html' in error.headers.get('Content-Type', ''),
                'edge_challenge_detected': edge}
    except ProbeError as error:
        return {'status': 'FAIL', 'reason': str(error)}
    except CandidateError as error:
        return {'status': 'FAIL', 'reason': 'candidate_' + str(error)}
    except (TimeoutError, urllib.error.URLError, OSError):
        return {'status': 'FAIL', 'reason': 'network_or_timeout'}
    except (ValueError, KeyError, TypeError, IndexError):
        return {'status': 'FAIL', 'reason': 'protocol_shape_error'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', required=True)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--local-config', type=Path)
    parser.add_argument('--key-stdin', action='store_true', help='Read one secret line from a non-echoing pipe.')
    parser.add_argument('--key-prompt', action='store_true', help='Read a secret from a terminal with echo disabled.')
    parser.add_argument('--provider', default='OpenAI')
    parser.add_argument('--checks', nargs='+', choices=['text', 'stream', 'tool', 'extraction'],
                        default=['text', 'stream', 'tool'])
    args = parser.parse_args()
    key = os.environ.get('MATHSTUDY_S0_API_KEY', '')
    headers = json.loads(os.environ.get('MATHSTUDY_S0_HEADERS_JSON', '{}'))
    if args.key_stdin:
        key = sys.stdin.readline().strip()
    if args.key_prompt:
        key = getpass.getpass('S0 key (hidden): ').strip()
    if args.local_config:
        provider = tomllib.loads(args.local_config.read_text(encoding='utf-8'))['model_providers'][args.provider]
        if provider['base_url'].rstrip('/') != require_base(args.base_url):
            parser.error('Local provider endpoint does not match the explicitly selected endpoint.')
        key = provider.get('experimental_bearer_token', '')
        headers = provider.get('http_headers', {})
    if not key:
        parser.error('Set MATHSTUDY_S0_API_KEY locally; never pass a key as a command argument.')
    probe = ResponsesProbe(args.base_url, key, args.model, headers)
    results = {'checked_at': datetime.now(timezone.utc).isoformat(), 'protocol': 'responses',
               'model': args.model, 'reasoning_effort': 'medium', 'store_requested': False,
               'environment': 'DESKTOP_REAL_PROVIDER'}
    for name in args.checks:
        results[name] = safe_run(getattr(probe, name + '_probe'))
        print(json.dumps({name: results[name]}, ensure_ascii=False), flush=True)
        if results[name]['status'] != 'PASS':
            break  # Fail once; no protocol/model fallback or repeated billed requests.
    for name in args.checks:
        results.setdefault(name, {'status': 'NOT_RUN'})
    results.update(android_ui_cancel_error='NOT_RUN', vision='NOT_RUN')
    target = ROOT / ('qa/s0/private/provider-responses.json' if args.checks == ['text', 'stream', 'tool']
                     else 'qa/s0/private/provider-responses-extra.json')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    return 0 if all(results[name]['status'] == 'PASS' for name in args.checks) else 1


if __name__ == '__main__':
    raise SystemExit(main())
