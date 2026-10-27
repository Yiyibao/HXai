"""Dedicated, opt-in MCP Python sandbox. No host execution, package downloads or retries.

Run only against a trusted private Linux Docker daemon and explicitly approved image
and script digests. This is a tool adapter, never a second Agent scheduler.
"""
import argparse
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import sqlite3
import subprocess
import threading
import time


def digest(data):
    return hashlib.sha256(data.encode() if isinstance(data, str) else data).hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False)


def strict_json(value):
    def pairs(items):
        out = {}
        for key, item in items:
            if key in out:
                raise ValueError('Duplicate JSON key')
            out[key] = item
        return out
    return json.loads(value, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Non-finite JSON')))


class Sandbox:
    def __init__(self, root, image, script_id, script, script_sha256, docker_host, token, python='/usr/bin/python3'):
        self.root = Path(root).resolve()
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        if not re.fullmatch(r'sha256:[a-f0-9]{64}', image) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', script_id):
            raise ValueError('Immutable image and script identity required')
        if not docker_host.startswith('unix:///') or docker_host == 'unix:///var/run/docker.sock':
            raise ValueError('Explicit private Docker socket required')
        if len(token) < 32 or not re.fullmatch(r'/[a-zA-Z0-9_./-]+', python):
            raise ValueError('Strong dedicated token / fixed Python path required')
        self.image, self.script_id, self.python = image, script_id, python
        self.code = Path(script).read_bytes()
        if len(self.code) > 262144 or digest(self.code) != script_sha256:
            raise ValueError('Approved script hash differs')
        self.code.decode('utf-8', errors='strict')
        self.code_hash, self.token = script_sha256, token
        self.env = {'PATH': '/usr/bin:/bin', 'DOCKER_HOST': docker_host}
        self.lock = threading.Lock()
        self.db = self.root / 'journal.sqlite'
        with self.connect() as db:
            version = db.execute('PRAGMA user_version').fetchone()[0]
            if version not in (0, 1, 2):
                raise ValueError('Future journal preserved')
            db.execute('CREATE TABLE IF NOT EXISTS calls(run_id TEXT PRIMARY KEY,input_hash TEXT NOT NULL,script_hash TEXT NOT NULL,state TEXT NOT NULL,container TEXT NOT NULL,receipt TEXT)')
            if version < 2:
                # Old results/UNKNOWN remain, without guessing a new profile owner.
                db.execute("ALTER TABLE calls ADD COLUMN profile TEXT NOT NULL DEFAULT 'legacy-unassigned'")
            db.execute('PRAGMA user_version=2')
        os.chmod(self.db, 0o600)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.db, timeout=10)
        try:
            db.execute('PRAGMA synchronous=FULL')
            with db:
                yield db
        finally:
            db.close()

    def docker(self, *args, timeout=15):
        return subprocess.run(['/usr/bin/docker', *args], env=self.env, capture_output=True, check=True, timeout=timeout)

    def qualify(self):
        info = strict_json(self.docker('info', '--format', '{{json .}}').stdout)
        if info['OSType'] != 'linux' or not all(info.get(k) for k in ('MemoryLimit', 'SwapLimit', 'CpuCfsQuota', 'PidsLimit')):
            raise ValueError('Required kernel isolation unavailable')
        image = strict_json(self.docker('image', 'inspect', self.image).stdout)[0]
        if image['Id'] != self.image:
            raise ValueError('Image identity changed')
        # Image-supplied environment can carry secrets or change the runtime. Reject
        # all but the small documented base-runtime set; never inherit host env.
        safe = {'PATH', 'LANG', 'LC_ALL', 'TZ', 'PYTHON_VERSION', 'PYTHON_SHA256', 'GPG_KEY', 'DEBIAN_FRONTEND',
                'HOME', 'PYTHONDONTWRITEBYTECODE', 'SAL_USE_VCLPLUGIN'}
        if any(v.split('=', 1)[0] not in safe for v in image['Config'].get('Env', [])):
            raise ValueError('Image environment not explicitly approved')

    def schema(self):
        return {'type': 'object', 'properties': {
            'script_id': {'type': 'string', 'enum': [self.script_id]},
            'script_sha256': {'type': 'string', 'enum': [self.code_hash]},
            'run_id': {'type': 'string', 'minLength': 64, 'maxLength': 64},
            'input_json': {'type': 'string', 'maxLength': 32768}},
            'required': ['script_id', 'script_sha256', 'input_json', 'run_id'], 'additionalProperties': False}

    def tools(self):
        return [{'name': 'run_python', 'inputSchema': self.schema()},
                {'name': 'read_result', 'inputSchema': {'type': 'object', 'properties': {'run_id': {'type': 'string'}}, 'required': ['run_id'], 'additionalProperties': False}}]

    def read(self, run_id, profile='local-admin'):
        if not re.fullmatch(r'[a-f0-9]{64}', run_id):
            raise ValueError('Invalid invocation identity')
        with self.connect() as db:
            row = db.execute('SELECT state,receipt FROM calls WHERE run_id=? AND profile=?', (run_id, profile)).fetchone()
        if row is None:
            raise ValueError('Unknown invocation')
        return strict_json(row[1]) if row[1] is not None else {'run_id': run_id, 'status': 'UNKNOWN'}

    def execute(self, args, profile='local-admin'):
        if set(args) != {'run_id', 'script_id', 'script_sha256', 'input_json'}:
            raise ValueError('Unsupported sandbox arguments')
        run_id, source = args['run_id'], args['input_json']
        if not isinstance(source, str) or len(source.encode()) > 32768 or not re.fullmatch(r'[a-f0-9]{64}', run_id):
            raise ValueError('Input limit or identity invalid')
        strict_json(source)
        if args['script_id'] != self.script_id or args['script_sha256'] != self.code_hash:
            raise ValueError('Code approval differs')
        input_hash = digest(source)
        container = 'hxai-skill-' + run_id
        with self.lock, self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT input_hash,script_hash,profile FROM calls WHERE run_id=?', (run_id,)).fetchone()
            if old:
                if old != (input_hash, self.code_hash, profile):
                    raise ValueError('Idempotency key intent differs')
                return self.read(run_id, profile)
            db.execute('INSERT INTO calls VALUES(?,?,?,?,?,NULL,?)', (run_id, input_hash, self.code_hash, 'IN_FLIGHT', container, profile))
        # A crash anywhere below leaves IN_FLIGHT/UNKNOWN. Restart/read never runs it again.
        folder = self.root / run_id
        folder.mkdir(mode=0o755)
        script = folder / 'script.py'
        script.write_bytes(self.code)
        script.chmod(0o444)
        created = self.docker('create', '--name', container, '--label', 'hxai.skill.run=' + run_id,
            '--network', 'none', '--read-only', '--user', '65534:65534', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--pids-limit', '32', '--cpus', '1',
            '--memory', '128m', '--memory-swap', '128m', '--ulimit', 'nofile=64:64',
            '--ulimit', 'fsize=65536:65536', '--tmpfs', '/tmp:rw,noexec,nosuid,nodev,size=16m',
            '--mount', f'type=bind,source={folder},target=/payload,readonly',
            '--workdir', '/tmp', '--log-driver', 'none', '-i', '--entrypoint', self.python,
            self.image, '-I', '-B', '/payload/script.py').stdout.strip()
        if not re.fullmatch(rb'[a-f0-9]{64}', created):
            raise ValueError('Container identity unavailable')
        inspect = strict_json(self.docker('inspect', container).stdout)[0]
        policy = inspect['HostConfig']
        if not (policy['NetworkMode'] == 'none' and policy['ReadonlyRootfs'] and policy['Memory'] == 134217728 and
                policy['MemorySwap'] == 134217728 and policy['PidsLimit'] == 32 and policy['NanoCpus'] == 1000000000 and
                policy['CapDrop'] == ['ALL'] and 'no-new-privileges' in policy['SecurityOpt'] and
                inspect['Config']['User'] == '65534:65534' and len(inspect['Mounts']) == 1 and not inspect['Mounts'][0]['RW']):
            raise ValueError('Container isolation readback differs')
        process = subprocess.Popen(['/usr/bin/docker', 'start', '-ai', container], env=self.env,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        process.stdin.write(source.encode()); process.stdin.close()
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ, 'out')
        selector.register(process.stderr, selectors.EVENT_READ, 'err')
        output, errors = bytearray(), bytearray()
        deadline = time.monotonic() + 15
        failed = False
        try:
            while selector.get_map():
                if time.monotonic() > deadline:
                    failed = True
                    break
                for key, _ in selector.select(0.1):
                    chunk = os.read(key.fileobj.fileno(), 4096)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    (output if key.data == 'out' else errors).extend(chunk)
                    if len(output) + len(errors) > 65536:
                        failed = True
                        break
                if failed:
                    break
            if failed:
                try:
                    self.docker('kill', container)
                except subprocess.CalledProcessError:
                    # A fast producer may exit between our output limit and kill.
                    # Only an independent stopped-container readback resolves it.
                    if strict_json(self.docker('inspect', container).stdout)[0]['State']['Running']:
                        raise
                process.kill()
            process.wait(timeout=10)
        finally:
            selector.close()
            if process.poll() is None:
                # Killing the CLI is not proof the container stopped. Keep UNKNOWN.
                process.kill(); process.wait()
            process.stdout.close(); process.stderr.close()
        final = strict_json(self.docker('inspect', container).stdout)[0]
        if final['State']['Running']:
            raise ValueError('Container termination not confirmed; UNKNOWN retained')
        good = not failed and final['State']['ExitCode'] == 0 and process.returncode == 0
        text = ''
        if good:
            try:
                text = output.decode('utf-8', errors='strict')
                strict_json(text)
            except (ValueError, UnicodeError):
                good, text = False, ''
        receipt = {'run_id': run_id, 'status': 'SUCCEEDED' if good else 'FAILED', 'script_sha256': self.code_hash,
            'input_sha256': input_hash, 'output_json': text if good else '', 'output_sha256': digest(text if good else ''),
            'image': self.image, 'isolation': 'linux-container-v1'}
        with self.connect() as db:
            db.execute('UPDATE calls SET state=?,receipt=? WHERE run_id=? AND state=?',
                (receipt['status'], encoded(receipt), run_id, 'IN_FLIGHT'))
        return receipt


def handler(sandbox):
    class Handler(BaseHTTPRequestHandler):
        # Every successful response has an explicit length. Keep the connection
        # alive consistently; an implicit HTTP/1.0 close can race a no-retry
        # client's next MCP notification on the pooled socket.
        protocol_version = 'HTTP/1.1'
        def setup(self):
            super().setup()
            self.connection.settimeout(10)
        def log_message(self, *_):
            pass  # Never log tokens, code, arguments, results or raw errors.

        def do_POST(self):
            # API binds loopback; put an explicitly configured TLS reverse proxy in
            # front for remote use. Browser origins are never accepted here.
            if self.path != '/mcp' or self.headers.get('Origin') is not None or not secrets.compare_digest(
                    self.headers.get('Authorization', ''), 'Bearer ' + sandbox.token):
                self.send_error(403); return
            try:
                length = int(self.headers.get('Content-Length', '-1'))
                if not 0 < length <= 65536 or self.headers.get_content_type() != 'application/json':
                    raise ValueError('Invalid request')
                request = strict_json(self.rfile.read(length))
                profile = self.headers.get('X-HXai-Profile', '')
                if not re.fullmatch(r'[a-f0-9]{64}', profile):
                    raise ValueError('Missing profile binding')
                if request.get('jsonrpc') != '2.0':
                    raise ValueError('Invalid JSON-RPC')
                method, params = request['method'], request.get('params', {})
                if method == 'notifications/initialized':
                    self.send_response(202); self.send_header('Content-Length', '0'); self.end_headers(); return
                if method == 'initialize':
                    if params['protocolVersion'] not in ('2025-03-26', '2025-06-18', '2025-11-25'):
                        raise ValueError('Unsupported version')
                    result = {'protocolVersion': params['protocolVersion'], 'capabilities': {'tools': {}},
                              'serverInfo': {'name': 'hxai-skill-sandbox', 'version': '1'},
                              '_meta': {'hxai.sandbox': {'image': sandbox.image, 'isolation': 'linux-container-v1', 'dependencies': 'preinstalled-image-only'}}}
                elif method == 'tools/list':
                    result = {'tools': sandbox.tools()}
                elif method == 'tools/call':
                    if params['name'] == 'run_python':
                        receipt = sandbox.execute(params['arguments'], profile)
                    elif params['name'] == 'read_result' and set(params['arguments']) == {'run_id'}:
                        receipt = sandbox.read(params['arguments']['run_id'], profile)
                    else:
                        raise ValueError('Unsupported tool')
                    result = {'content': [{'type': 'text', 'text': encoded(receipt)}], 'isError': False}
                else:
                    raise ValueError('Unsupported method')
                body = encoded({'jsonrpc': '2.0', 'id': request['id'], 'result': result}).encode()
                self.send_response(200); self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
            except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
                # A failure after journal admission stays UNKNOWN; no retry/fallback.
                self.send_error(400, 'Request failed; query existing receipt before any new execution')
    return Handler


class BoundedHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, *args):
        self.requests = threading.BoundedSemaphore(8)
        super().__init__(*args)

    def process_request(self, request, address):
        if not self.requests.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.requests.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.requests.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ('root', 'image', 'script-id', 'script', 'script-sha256', 'docker-host', 'token-file'):
        parser.add_argument('--' + field, required=True)
    parser.add_argument('--python', default='/usr/bin/python3')
    parser.add_argument('--port', type=int, default=18891)
    args = parser.parse_args()
    token_path = Path(args.token_file)
    if token_path.stat().st_mode & 0o077:
        parser.error('Token file must be owner-only')
    service = Sandbox(args.root, args.image, args.script_id, args.script, args.script_sha256,
        args.docker_host, token_path.read_text().strip(), args.python)
    service.qualify()
    server = BoundedHTTPServer(('127.0.0.1', args.port), handler(service))
    server.serve_forever()


if __name__ == '__main__':
    main()
