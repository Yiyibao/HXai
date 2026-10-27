import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

from sandbox_service import Sandbox, digest, strict_json

IMAGE = os.environ.get('A04_SANDBOX_IMAGE')
SOCKET = 'unix:///run/hxai-b06/docker.sock'
TOKEN = 'synthetic-a04-only-' + 'x' * 40

PROBE = b'''import json,sys,os,socket,time
x=json.load(sys.stdin)
mode=x.get("mode","sum")
if mode=="sum": print(json.dumps({"sum":sum(x["values"]),"label":"alpha beta"}))
elif mode=="isolation":
 def read(p):
  try: return open(p).read().strip()
  except OSError: return "unavailable"
 def writable(p):
  try:
   open(p,"w").write("sentinel");return True
  except OSError: return False
 c=socket.socket();c.settimeout(0.5)
 try: c.connect(("127.0.0.1",18891));network=True
 except OSError: network=False
 print(json.dumps({"uid":os.getuid(),"net":os.listdir("/sys/class/net"),"network":network,
 "root_write":writable("/root-sentinel"),"code_write":writable("/payload/script.py"),
 "socket":os.path.exists("/var/run/docker.sock"),"credential":os.getenv("A04_PRIVATE_SENTINEL"),
 "caps":[v for v in read("/proc/self/status").splitlines() if v.startswith(("CapEff:","NoNewPrivs:"))],
 "memory":read("/sys/fs/cgroup/memory.max"),"swap":read("/sys/fs/cgroup/memory.swap.max"),
 "pids":read("/sys/fs/cgroup/pids.max"),"cpu":read("/sys/fs/cgroup/cpu.max")}))
elif mode=="oversize": print("x"*70000)
elif mode=="invalid": print("success but not JSON")
elif mode=="exit": sys.exit(3)
elif mode=="loop":
 while True: pass
elif mode=="sleep": time.sleep(30);print("{}")
elif mode=="dependency":
 import importlib.util
 print(json.dumps({"arbitrary_dependency":importlib.util.find_spec("a04_never_installed") is not None}))
'''


class SandboxContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.script = self.root / 'input.py'
        self.script.write_bytes(PROBE)

    def tearDown(self):
        self.temp.cleanup()

    def service(self, sub='data'):
        return Sandbox(self.root / sub, IMAGE or ('sha256:' + 'a' * 64), 'approved-probe', self.script,
            digest(PROBE), SOCKET, TOKEN)

    def args(self, key='one', **inputs):
        return dict(run_id=digest(key), script_id='approved-probe', script_sha256=digest(PROBE), input_json=json.dumps(inputs))

    def test_code_update_requires_new_approval(self):
        self.script.write_bytes(PROBE + b'\nprint("changed")')
        with self.assertRaises(ValueError): self.service()

    def test_mcp_handshake_keeps_one_connection_without_client_reconnect(self):
        import http.client
        import threading
        from sandbox_service import BoundedHTTPServer, handler
        s = self.service()
        accepted = []
        class CountedServer(BoundedHTTPServer):
            def get_request(self):
                request, address = super().get_request()
                accepted.append(address)
                return request, address
        server = CountedServer(('127.0.0.1', 0), handler(s))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        client = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=3)
        try:
            for index, method in enumerate(['initialize', 'notifications/initialized', 'tools/list']):
                request = dict(jsonrpc='2.0', method=method)
                if method != 'notifications/initialized': request['id'] = index
                if method == 'initialize': request['params'] = dict(protocolVersion='2025-11-25')
                client.request('POST', '/mcp', json.dumps(request), {
                    'Content-Type': 'application/json', 'Authorization': 'Bearer '+TOKEN,
                    'X-HXai-Profile': digest('synthetic-profile')})
                response = client.getresponse()
                self.assertEqual(202 if method == 'notifications/initialized' else 200, response.status)
                response.read()
            self.assertEqual(1, len(accepted), 'MCP handshake required a connection replay/reconnect')
        finally:
            client.close();server.shutdown();server.server_close();thread.join(timeout=3)

    def test_default_daemon_and_mutable_images_are_rejected(self):
        for image, socket in [('python:latest', SOCKET), ('sha256:'+'a'*64, 'unix:///var/run/docker.sock')]:
            with self.assertRaises(ValueError): Sandbox(self.root/'bad',image,'id',self.script,digest(PROBE),socket,TOKEN)

    def test_duplicate_and_nonfinite_json_are_rejected(self):
        for v in ['{"a":1,"a":2}', 'NaN', 'Infinity']:
            with self.assertRaises(ValueError): strict_json(v)

    def test_preexisting_unknown_is_not_reexecuted_after_restart(self):
        service = self.service()
        def unavailable(*_args, **_kwargs): raise OSError('synthetic unavailable before create ACK')
        service.docker = unavailable
        with self.assertRaises(OSError): service.execute(self.args(mode='sum',values=[1,2]))
        reopened = self.service()
        reopened.docker = lambda *_a, **_k: self.fail('UNKNOWN was replayed')
        self.assertEqual('UNKNOWN',reopened.execute(self.args(mode='sum',values=[1,2]))['status'])
        with self.assertRaises(ValueError): reopened.execute(self.args(mode='sum',values=[3]))

    def test_input_shape_and_code_scope_are_enforced_before_journal(self):
        s = self.service()
        for a in [dict(self.args(), script_sha256='a'*64),dict(self.args(),host_path='/private'),dict(self.args(),input_json='not JSON')]:
            with self.assertRaises(ValueError): s.execute(a)
        with s.connect() as db: self.assertEqual(0,db.execute('SELECT count(*) FROM calls').fetchone()[0])

    def test_other_profile_cannot_read_or_reuse_invocation(self):
        s=self.service()
        s.docker=lambda *_a,**_k: (_ for _ in ()).throw(OSError('synthetic transport loss'))
        args=self.args(mode='sum',values=[1,2])
        with self.assertRaises(OSError):s.execute(args,profile='A')
        with self.assertRaises(ValueError):s.read(args['run_id'],profile='B')
        with self.assertRaises(ValueError):s.execute(args,profile='B')
        self.assertEqual('UNKNOWN',s.read(args['run_id'],profile='A')['status'])

    def test_old_schema_preserves_unknown_without_assigning_new_owner(self):
        import sqlite3
        folder=self.root/'legacy-data';folder.mkdir()
        db=sqlite3.connect(folder/'journal.sqlite')
        db.execute('CREATE TABLE calls(run_id TEXT PRIMARY KEY,input_hash TEXT NOT NULL,script_hash TEXT NOT NULL,state TEXT NOT NULL,container TEXT NOT NULL,receipt TEXT)')
        db.execute('INSERT INTO calls VALUES(?,?,?,?,?,NULL)',(digest('old'),digest('{}'),digest(PROBE),'IN_FLIGHT','old-container'))
        db.execute('PRAGMA user_version=1');db.commit();db.close()
        s=self.service('legacy-data')
        self.assertEqual('UNKNOWN',s.read(digest('old'),profile='legacy-unassigned')['status'])
        with self.assertRaises(ValueError):s.read(digest('old'),profile='new-owner')
        with s.connect() as db:self.assertEqual(2,db.execute('PRAGMA user_version').fetchone()[0])


@unittest.skipUnless(IMAGE and sys.platform == 'linux', 'Explicit owned Linux Docker qualification required')
class SandboxLinuxTest(SandboxContractTest):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='a04-', dir='/opt/hxai-a04/runs')
        self.root=Path(self.temp.name);self.script=self.root/'input.py';self.script.write_bytes(PROBE)
        self.service().qualify()

    def tearDown(self):
        # Keep raw journals/input/code for reproducible process and container evidence.
        self.temp._finalizer.detach()

    def execute(self, mode, **data):
        s=self.service('data-'+mode)
        args=self.args(key=self.root.name+mode,mode=mode,**data)
        result=s.execute(args)
        self.assertEqual(result,s.read(args['run_id']))
        inspect=s.docker('inspect','hxai-skill-'+args['run_id']).stdout
        (self.root / (mode+'-inspect.json')).write_bytes(inspect)
        (self.root / (mode+'-receipt.json')).write_text(json.dumps(result))
        return s,args,result

    def test_real_calculation_readback_and_dedup(self):
        s,args,result=self.execute('sum',values=[1,2,3])
        self.assertEqual('SUCCEEDED',result['status']);self.assertEqual(6,strict_json(result['output_json'])['sum'])
        s.docker=lambda *_a,**_k:self.fail('Committed invocation reexecuted')
        self.assertEqual(result,s.execute(args))

    def test_actual_kernel_isolation_and_no_host_secrets(self):
        os.environ['A04_PRIVATE_SENTINEL']='must-never-enter-container'
        _,_,result=self.execute('isolation');self.assertEqual('SUCCEEDED',result['status'])
        v=strict_json(result['output_json'])
        self.assertEqual(65534,v['uid']);self.assertEqual(['lo'],v['net']);self.assertFalse(v['network'])
        for k in ('root_write','code_write','socket'):self.assertFalse(v[k])
        self.assertIsNone(v['credential']);self.assertEqual('134217728',v['memory']);self.assertEqual('0',v['swap']);self.assertEqual('32',v['pids'])
        self.assertTrue(v['cpu'].startswith('100000 100000'))
        self.assertIn('CapEff:\t0000000000000000',v['caps']);self.assertIn('NoNewPrivs:\t1',v['caps'])

    def test_invalid_stdout_never_becomes_success(self):
        self.assertEqual('FAILED',self.execute('invalid')[2]['status'])

    def test_output_limit_and_exit_failure(self):
        self.assertEqual('FAILED',self.execute('oversize')[2]['status']);self.assertEqual('FAILED',self.execute('exit')[2]['status'])

    def test_wall_timeout_is_stopped_and_observed(self):
        self.assertEqual('FAILED',self.execute('loop')[2]['status'])

    def test_missing_dependency_does_not_install_it(self):
        result=self.execute('dependency')[2]
        self.assertFalse(strict_json(result['output_json'])['arbitrary_dependency'])

    def test_service_process_kill_preserves_unknown_and_never_replays(self):
        s=self.service();args=self.args(key=self.root.name,mode='sleep')
        driver=self.root/'driver.py'
        driver.write_text('import sys,json;sys.path.insert(0,'+repr(str(Path(__file__).parent))+');from sandbox_service import Sandbox;'
            +'s=Sandbox('+','.join(repr(v) for v in [str(s.root),s.image,s.script_id,str(self.script),s.code_hash,SOCKET,TOKEN])+');s.execute('+repr(args)+')')
        proc=subprocess.Popen([sys.executable,str(driver)])
        container='hxai-skill-'+args['run_id']
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            try:
                running=strict_json(s.docker('inspect',container).stdout)[0]['State']['Running']
                if running:break
            except subprocess.CalledProcessError:pass
            time.sleep(.1)
        else:self.fail('Container did not start')
        proc.kill();proc.wait(timeout=5)
        self.assertEqual('UNKNOWN',s.read(args['run_id'])['status'])
        reopened=self.service();self.assertEqual('UNKNOWN',reopened.execute(args)['status'])
        s.docker('kill',container)
        self.assertFalse(strict_json(s.docker('inspect',container).stdout)[0]['State']['Running'])
        self.assertEqual('UNKNOWN',reopened.read(args['run_id'])['status'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
