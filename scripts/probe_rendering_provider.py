"""Single bounded synthetic Responses request; never save credentials or raw service bodies."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import socket
import time
import urllib.error
import urllib.request

from probe_provider import NoRedirect
from probe_responses import require_base


def probe(base, model, key):
    started = time.monotonic()
    result = dict(model=model, protocol='responses', reasoning_effort='low', store_requested=False,
                  environment='DESKTOP_REAL_PROVIDER', status='FAIL', events={})
    body = dict(model=model, input='Synthetic connection check. Reply exactly MATH_RENDERING_OK.',
                stream=True, store=False, reasoning={'effort': 'low'}, max_output_tokens=2048)
    request = urllib.request.Request(require_base(base) + '/responses', data=json.dumps(body).encode(),
                                    headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json',
                                             'Accept': 'text/event-stream', 'User-Agent': 'MathStudyAgent-QA/1.0'})
    events = Counter()
    marker = False
    completed = False
    received = 0
    known = {'response.created', 'response.in_progress', 'response.output_text.delta',
             'response.output_item.added', 'response.output_item.done', 'response.completed',
             'response.failed', 'response.incomplete', 'error'}
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=20) as response:
            result['http_status'] = response.status
            if 'text/event-stream' not in response.headers.get('Content-Type', ''):
                result['reason'] = 'not_event_stream'
                return result
            for raw in response:
                received += len(raw)
                if time.monotonic() - started > 60 or received > 256_000:
                    result['reason'] = 'bounded_stream_limit'
                    break
                line = raw.decode('utf-8').strip()
                if not line.startswith('data:') or line[5:].strip() == '[DONE]':
                    continue
                payload = json.loads(line[5:])
                kind = payload.get('type', '')
                events[kind if kind in known else 'other'] += 1
                if kind == 'response.output_text.delta':
                    marker = marker or 'MATH_RENDERING_OK' in payload.get('delta', '')
                if kind in {'error', 'response.failed', 'response.incomplete'}:
                    result['reason'] = kind
                    break
                if kind == 'response.completed':
                    completed = True
                    text = ''.join(part.get('text', '') for item in payload.get('response', {}).get('output', [])
                                   for part in item.get('content', []) if part.get('type') == 'output_text')
                    marker = marker or 'MATH_RENDERING_OK' in text
                    break
            result['marker_received'] = marker
            result['completed'] = completed
            result['status'] = 'PASS' if marker and completed else 'FAIL'
            if result['status'] == 'FAIL':
                result.setdefault('reason', 'terminal_or_marker_missing')
    except urllib.error.HTTPError as error:
        result.update(http_status=error.code, reason='http_rejection')
        error.close()
    except urllib.error.URLError as error:
        result['reason'] = 'transport_timeout' if isinstance(error.reason, (TimeoutError, socket.timeout)) else 'transport_failure'
    except (TimeoutError, socket.timeout):
        result['reason'] = 'transport_timeout'
    except Exception:
        result['reason'] = 'transport_or_protocol_failure'
    finally:
        result['events'] = dict(events)
        result['elapsed_seconds'] = round(time.monotonic() - started, 3)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--model', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    key = os.environ.pop('MATHSTUDY_S0_API_KEY', '')
    if not key:
        parser.error('Missing environment credential; never put keys in CLI arguments.')
    result = probe(args.base_url, args.model, key)
    result['checked_at'] = datetime.now(timezone.utc).isoformat()
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
